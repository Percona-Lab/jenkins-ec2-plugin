package hudson.plugins.ec2;

import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsResponse;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsState;
import software.amazon.awssdk.services.ec2.model.ModifyInstanceMetadataOptionsRequest;

/**
 * Brings the live instance metadata options of a spot instance in line with its template before the agent starts.
 * Spot requests with a bid price cannot carry metadata options, so the instance takes them from the AMI.
 * Opt-in through the {@value #ENFORCE_PROPERTY} system property.
 */
@Restricted(NoExternalUse.class)
final class InstanceMetadataOptionsEnforcer {

    private static final Logger LOGGER = Logger.getLogger(InstanceMetadataOptionsEnforcer.class.getName());

    static final String ENFORCE_PROPERTY = "hudson.plugins.ec2.enforceMetadataOptions";
    static final String TIMEOUT_PROPERTY = ENFORCE_PROPERTY + ".timeoutMs";
    static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 2_000L;

    private InstanceMetadataOptionsEnforcer() {}

    static boolean isEnabled() {
        return SystemProperties.getBoolean(ENFORCE_PROPERTY, false);
    }

    /**
     * Throws when the options cannot be confirmed, so that the caller aborts the launch.
     * Does nothing when the switch is off, the node is not a spot node, or the template does not manage metadata.
     */
    static void enforceIfEnabled(EC2Computer computer, TaskListener listener) throws IOException, InterruptedException {
        if (!isEnabled()) {
            return;
        }
        EC2AbstractSlave node = computer.getNode();
        if (!(node instanceof EC2SpotSlave)) {
            return;
        }
        SlaveTemplate template = computer.getSlaveTemplate();
        if (template == null) {
            throw fail(computer, listener, "no agent template found, cannot check metadata options");
        }
        if (!Boolean.TRUE.equals(template.getMetadataSupported())) {
            return;
        }
        String instanceId = computer.getInstanceId();
        if (StringUtils.isEmpty(instanceId)) {
            throw fail(computer, listener, "instance id not known yet, cannot check metadata options");
        }
        EC2Cloud cloud = computer.getCloud();
        if (cloud == null) {
            throw fail(computer, listener, "no cloud found, cannot check metadata options of " + instanceId);
        }

        InstanceMetadataEndpointState endpoint = Boolean.FALSE.equals(template.getMetadataEndpointEnabled())
                ? InstanceMetadataEndpointState.DISABLED
                : InstanceMetadataEndpointState.ENABLED;
        HttpTokensState tokens = Boolean.FALSE.equals(template.getMetadataTokensRequired())
                ? HttpTokensState.OPTIONAL
                : HttpTokensState.REQUIRED;
        int hops = template.getMetadataHopsLimit() == null
                ? EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT
                : template.getMetadataHopsLimit();

        InstanceMetadataOptionsResponse live = describe(computer, listener, cloud, instanceId);
        if (applied(live, endpoint, tokens, hops)) {
            LOGGER.log(Level.FINE, "Metadata options of {0} already match the template", instanceId);
            return;
        }
        if (matches(live, endpoint, tokens, hops)) {
            log(listener, "Metadata options of " + instanceId + " match the template, waiting for state applied");
        } else {
            modify(computer, listener, cloud, instanceId, live, endpoint, tokens, hops);
        }

        long timeoutMs = SystemProperties.getLong(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            live = describe(computer, listener, cloud, instanceId);
            if (applied(live, endpoint, tokens, hops)) {
                log(listener, "Metadata options of " + instanceId + " applied: " + summary(live));
                return;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw fail(
                        computer,
                        listener,
                        "Timed out after " + timeoutMs + " ms waiting for metadata options of " + instanceId
                                + " to be applied, last seen " + summary(live));
            }
            Thread.sleep(Math.min(POLL_INTERVAL_MS, remaining));
        }
    }

    private static void modify(
            EC2Computer computer,
            TaskListener listener,
            EC2Cloud cloud,
            String instanceId,
            InstanceMetadataOptionsResponse live,
            InstanceMetadataEndpointState endpoint,
            HttpTokensState tokens,
            int hops)
            throws IOException {
        log(
                listener,
                "Metadata options of " + instanceId + " are " + summary(live) + ", applying template values httpTokens="
                        + tokens + " hopLimit=" + hops + " httpEndpoint=" + endpoint);
        try {
            cloud.connect()
                    .modifyInstanceMetadataOptions(ModifyInstanceMetadataOptionsRequest.builder()
                            .instanceId(instanceId)
                            .httpEndpoint(endpoint)
                            .httpTokens(tokens)
                            .httpPutResponseHopLimit(hops)
                            .build());
        } catch (SdkException e) {
            throw fail(
                    computer,
                    listener,
                    "failed to modify metadata options of " + instanceId + ": " + e.getMessage(),
                    e);
        }
    }

    private static InstanceMetadataOptionsResponse describe(
            EC2Computer computer, TaskListener listener, EC2Cloud cloud, String instanceId) throws IOException {
        try {
            Instance instance = CloudHelper.getInstance(instanceId, cloud);
            return instance == null ? null : instance.metadataOptions();
        } catch (SdkException e) {
            throw fail(
                    computer,
                    listener,
                    "failed to describe metadata options of " + instanceId + ": " + e.getMessage(),
                    e);
        }
    }

    private static boolean applied(
            InstanceMetadataOptionsResponse live,
            InstanceMetadataEndpointState endpoint,
            HttpTokensState tokens,
            int hops) {
        return matches(live, endpoint, tokens, hops) && live.state() == InstanceMetadataOptionsState.APPLIED;
    }

    private static boolean matches(
            InstanceMetadataOptionsResponse live,
            InstanceMetadataEndpointState endpoint,
            HttpTokensState tokens,
            int hops) {
        return live != null
                && live.httpEndpoint() == endpoint
                && live.httpTokens() == tokens
                && Objects.equals(live.httpPutResponseHopLimit(), hops);
    }

    private static String summary(InstanceMetadataOptionsResponse live) {
        if (live == null) {
            return "unknown";
        }
        return "httpTokens=" + live.httpTokensAsString() + " hopLimit=" + live.httpPutResponseHopLimit()
                + " httpEndpoint=" + live.httpEndpointAsString() + " state=" + live.stateAsString();
    }

    private static void log(TaskListener listener, String message) {
        LOGGER.info(message);
        listener.getLogger().println(message);
    }

    private static IOException fail(EC2Computer computer, TaskListener listener, String reason) {
        return fail(computer, listener, reason, null);
    }

    private static IOException fail(EC2Computer computer, TaskListener listener, String reason, Throwable cause) {
        String message = "Aborting launch of " + computer.getName() + ": " + reason;
        LOGGER.warning(message);
        listener.getLogger().println(message);
        return new IOException(message, cause);
    }
}
