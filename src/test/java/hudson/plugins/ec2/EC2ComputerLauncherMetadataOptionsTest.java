package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsResponse;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsState;
import software.amazon.awssdk.services.ec2.model.ModifyInstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.ModifyInstanceMetadataOptionsResponse;
import software.amazon.awssdk.services.ec2.model.Reservation;

/**
 * Checks that {@link EC2ComputerLauncher#launch} brings the live instance metadata options of a spot
 * worker in line with its template before the agent is started, when the opt-in switch is set.
 */
class EC2ComputerLauncherMetadataOptionsTest {

    private static final String SWITCH = "hudson.plugins.ec2.enforceMetadataOptions";
    private static final String TIMEOUT = "hudson.plugins.ec2.enforceMetadataOptions.timeoutMs";
    private static final String INSTANCE_ID = "i-0123456789abcdef0";

    private Ec2Client ec2;
    private EC2Cloud cloud;
    private SlaveTemplate template;
    private EC2SpotSlave spotNode;
    private EC2Computer computer;
    private RecordingLauncher launcher;
    private ByteArrayOutputStream log;
    private TaskListener listener;

    /** Stands in for the real launchers and records whether stage 2 ran. */
    private static final class RecordingLauncher extends EC2ComputerLauncher {
        boolean scriptLaunched;

        @Override
        protected void launchScript(EC2Computer computer, TaskListener listener) {
            scriptLaunched = true;
        }
    }

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Client.class);
        cloud = mock(EC2Cloud.class);
        when(cloud.connect()).thenReturn(ec2);

        template = mock(SlaveTemplate.class);
        when(template.getMetadataSupported()).thenReturn(true);
        when(template.getMetadataEndpointEnabled()).thenReturn(true);
        when(template.getMetadataTokensRequired()).thenReturn(true);
        when(template.getMetadataHopsLimit()).thenReturn(1);

        spotNode = mock(EC2SpotSlave.class);
        computer = mockComputer(spotNode);

        launcher = new RecordingLauncher();
        log = new ByteArrayOutputStream();
        listener = new StreamTaskListener(log, StandardCharsets.UTF_8);
    }

    private EC2Computer mockComputer(EC2AbstractSlave node) {
        EC2Computer c = mock(EC2Computer.class);
        when(c.getNode()).thenReturn(node);
        when(c.getCloud()).thenReturn(cloud);
        when(c.getSlaveTemplate()).thenReturn(template);
        when(c.getInstanceId()).thenReturn(INSTANCE_ID);
        when(c.getName()).thenReturn("spot-worker");
        return c;
    }

    private static DescribeInstancesResponse describe(
            HttpTokensState tokens,
            int hops,
            InstanceMetadataEndpointState endpoint,
            InstanceMetadataOptionsState state) {
        Instance instance = Instance.builder()
                .instanceId(INSTANCE_ID)
                .metadataOptions(InstanceMetadataOptionsResponse.builder()
                        .httpTokens(tokens)
                        .httpPutResponseHopLimit(hops)
                        .httpEndpoint(endpoint)
                        .state(state)
                        .build())
                .build();
        return DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(instance).build())
                .build();
    }

    private static DescribeInstancesResponse amiDefaults() {
        return describe(
                HttpTokensState.REQUIRED,
                2,
                InstanceMetadataEndpointState.ENABLED,
                InstanceMetadataOptionsState.APPLIED);
    }

    private static DescribeInstancesResponse templateValues(InstanceMetadataOptionsState state) {
        return describe(HttpTokensState.REQUIRED, 1, InstanceMetadataEndpointState.ENABLED, state);
    }

    private String logText() {
        return log.toString(StandardCharsets.UTF_8);
    }

    @Test
    @ClearSystemProperty(key = SWITCH)
    void switchOffMakesNoAwsCall() {
        launcher.launch(computer, listener);

        assertTrue(launcher.scriptLaunched);
        verifyNoInteractions(ec2);
        verify(cloud, never()).connect();
        verify(spotNode, never()).terminate();
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "false")
    void switchExplicitlyFalseMakesNoAwsCall() {
        launcher.launch(computer, listener);

        assertTrue(launcher.scriptLaunched);
        verifyNoInteractions(ec2);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithMismatchModifiesOnceWithTemplateValues() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(amiDefaults())
                .thenReturn(templateValues(InstanceMetadataOptionsState.APPLIED));
        when(ec2.modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class)))
                .thenReturn(ModifyInstanceMetadataOptionsResponse.builder().build());

        launcher.launch(computer, listener);

        ArgumentCaptor<ModifyInstanceMetadataOptionsRequest> captor =
                ArgumentCaptor.forClass(ModifyInstanceMetadataOptionsRequest.class);
        verify(ec2, times(1)).modifyInstanceMetadataOptions(captor.capture());
        ModifyInstanceMetadataOptionsRequest request = captor.getValue();
        assertEquals(INSTANCE_ID, request.instanceId());
        assertEquals(HttpTokensState.REQUIRED, request.httpTokens());
        assertEquals(1, request.httpPutResponseHopLimit());
        assertEquals(InstanceMetadataEndpointState.ENABLED, request.httpEndpoint());
        assertTrue(launcher.scriptLaunched);
        verify(spotNode, never()).terminate();
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWaitsForAppliedStateBeforeLaunching() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(amiDefaults())
                .thenReturn(templateValues(InstanceMetadataOptionsState.PENDING))
                .thenReturn(templateValues(InstanceMetadataOptionsState.APPLIED));
        when(ec2.modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class)))
                .thenReturn(ModifyInstanceMetadataOptionsResponse.builder().build());

        launcher.launch(computer, listener);

        verify(ec2, times(3)).describeInstances(any(DescribeInstancesRequest.class));
        verify(ec2, times(1)).modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class));
        assertTrue(launcher.scriptLaunched);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithMatchingOptionsMakesNoModifyCall() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(templateValues(InstanceMetadataOptionsState.APPLIED));

        launcher.launch(computer, listener);

        verify(ec2, times(1)).describeInstances(any(DescribeInstancesRequest.class));
        verify(ec2, never()).modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class));
        assertTrue(launcher.scriptLaunched);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithMatchingPendingOptionsWaitsWithoutModify() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(templateValues(InstanceMetadataOptionsState.PENDING))
                .thenReturn(templateValues(InstanceMetadataOptionsState.APPLIED));

        launcher.launch(computer, listener);

        verify(ec2, times(2)).describeInstances(any(DescribeInstancesRequest.class));
        verify(ec2, never()).modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class));
        assertTrue(launcher.scriptLaunched);
        verify(spotNode, never()).terminate();
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithFailingDescribeAbortsLaunch() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenThrow(AwsServiceException.builder()
                        .message("not authorized")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("UnauthorizedOperation")
                                .build())
                        .build());

        launcher.launch(computer, listener);

        assertFalse(launcher.scriptLaunched);
        verify(spotNode, times(1)).terminate();
        verify(ec2, never()).modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class));
        assertTrue(logText().contains("Aborting launch"), logText());
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithMissingTemplateAbortsLaunch() {
        when(computer.getSlaveTemplate()).thenReturn(null);

        launcher.launch(computer, listener);

        assertFalse(launcher.scriptLaunched);
        verify(spotNode, times(1)).terminate();
        verifyNoInteractions(ec2);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithFailingModifyAbortsLaunch() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(amiDefaults());
        when(ec2.modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class)))
                .thenThrow(AwsServiceException.builder()
                        .message("not authorized")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("UnauthorizedOperation")
                                .build())
                        .build());

        launcher.launch(computer, listener);

        assertFalse(launcher.scriptLaunched);
        verify(spotNode, times(1)).terminate();
        assertTrue(logText().contains(INSTANCE_ID), logText());
        assertTrue(logText().contains("metadata options"), logText());
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    @SetSystemProperty(key = TIMEOUT, value = "1")
    void switchOnAbortsLaunchWhenOptionsNeverApply() {
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(amiDefaults())
                .thenReturn(templateValues(InstanceMetadataOptionsState.PENDING));
        when(ec2.modifyInstanceMetadataOptions(any(ModifyInstanceMetadataOptionsRequest.class)))
                .thenReturn(ModifyInstanceMetadataOptionsResponse.builder().build());

        launcher.launch(computer, listener);

        assertFalse(launcher.scriptLaunched);
        verify(spotNode, times(1)).terminate();
        assertTrue(logText().contains("Timed out"), logText());
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithMetadataUnsupportedMakesNoAwsCall() {
        when(template.getMetadataSupported()).thenReturn(false);

        launcher.launch(computer, listener);

        assertTrue(launcher.scriptLaunched);
        verifyNoInteractions(ec2);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnLeavesOnDemandNodesAlone() {
        EC2OndemandSlave ondemandNode = mock(EC2OndemandSlave.class);
        EC2Computer ondemandComputer = mockComputer(ondemandNode);

        launcher.launch(ondemandComputer, listener);

        assertTrue(launcher.scriptLaunched);
        verifyNoInteractions(ec2);
    }

    @Test
    @SetSystemProperty(key = SWITCH, value = "true")
    void switchOnWithUnknownInstanceIdAbortsLaunch() {
        when(computer.getInstanceId()).thenReturn(null);

        launcher.launch(computer, listener);

        assertFalse(launcher.scriptLaunched);
        verify(spotNode, times(1)).terminate();
        verifyNoInteractions(ec2);
    }
}
