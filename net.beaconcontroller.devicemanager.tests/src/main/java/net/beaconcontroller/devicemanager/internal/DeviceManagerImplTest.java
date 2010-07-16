package net.beaconcontroller.devicemanager.internal;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.util.Arrays;

import net.beaconcontroller.core.IOFSwitch;
import net.beaconcontroller.devicemanager.Device;
import net.beaconcontroller.packet.Data;
import net.beaconcontroller.packet.Ethernet;
import net.beaconcontroller.packet.IPacket;
import net.beaconcontroller.packet.IPv4;
import net.beaconcontroller.packet.UDP;
import net.beaconcontroller.test.BeaconTestCase;
import net.beaconcontroller.test.MockBeaconProvider;

import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketIn.OFPacketInReason;

/**
 *
 * @author David Erickson (derickso@stanford.edu)
 */
public class DeviceManagerImplTest extends BeaconTestCase {
    protected OFPacketIn packetIn;
    protected IPacket testPacket;
    protected byte[] testPacketSerialized;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Build our test packet
        this.testPacket = new Ethernet()
            .setDestinationMACAddress("00:11:22:33:44:55")
            .setSourceMACAddress("00:44:33:22:11:00")
            .setEtherType(Ethernet.TYPE_IPv4)
            .setPayload(
                new IPv4()
                .setTtl((byte) 128)
                .setSourceAddress("192.168.1.1")
                .setDestinationAddress("192.168.1.2")
                .setPayload(new UDP()
                            .setSourcePort((short) 5000)
                            .setDestinationPort((short) 5001)
                            .setPayload(new Data(new byte[] {0x01}))));
        this.testPacketSerialized = testPacket.serialize();

        // Build the PacketIn
        this.packetIn = new OFPacketIn()
            .setBufferId(-1)
            .setInPort((short) 1)
            .setPacketData(this.testPacketSerialized)
            .setReason(OFPacketInReason.NO_MATCH)
            .setTotalLength((short) this.testPacketSerialized.length);
    }

    protected DeviceManagerImpl getDeviceManager() {
        return (DeviceManagerImpl) getApplicationContext().getBean("deviceManager");
    }

    protected MockBeaconProvider getMockBeaconProvider() {
        return (MockBeaconProvider) getApplicationContext().getBean("mockBeaconProvider");
    }

    public void testDeviceDiscover() throws Exception {
        DeviceManagerImpl deviceManager = getDeviceManager();
        // TODO need to mockup topology and inject it
        MockBeaconProvider mockBeaconProvider = getMockBeaconProvider();
        byte[] dataLayerSource = ((Ethernet)this.testPacket).getSourceMACAddress();
        
        // build our expected Device
        Device device = new Device();
        device.setDataLayerAddress(dataLayerSource);
        device.setSwId(1L);
        device.setSwPort((short)1);

        // Mock up our expected behavior
        IOFSwitch mockSwitch = createMock(IOFSwitch.class);
        expect(mockSwitch.getId()).andReturn(1L);

        // Start recording the replay on the mocks
        replay(mockSwitch);
        // Get the listener and trigger the packet in
        mockBeaconProvider.dispatchMessage(mockSwitch, this.packetIn);

        // Verify the replay matched our expectations
        verify(mockSwitch);

        // Verify the device
        assertEquals(device, deviceManager.getDeviceByDataLayerAddress(Arrays.hashCode(dataLayerSource)));
    }
}