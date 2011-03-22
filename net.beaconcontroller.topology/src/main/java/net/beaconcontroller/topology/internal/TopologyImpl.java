package net.beaconcontroller.topology.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.beaconcontroller.core.IBeaconProvider;
import net.beaconcontroller.core.IOFMessageListener;
import net.beaconcontroller.core.IOFSwitch;
import net.beaconcontroller.core.IOFSwitchListener;
import net.beaconcontroller.packet.Ethernet;
import net.beaconcontroller.packet.LLDP;
import net.beaconcontroller.packet.LLDPTLV;
//import net.beaconcontroller.storage.CompoundPredicate;
import net.beaconcontroller.storage.IResultSet;
import net.beaconcontroller.storage.IStorageSource;
import net.beaconcontroller.storage.OperatorPredicate;
import net.beaconcontroller.topology.ITopology;
import net.beaconcontroller.topology.LinkTuple;
import net.beaconcontroller.topology.LinkInfo;
import net.beaconcontroller.topology.SwitchPortTuple;
import net.beaconcontroller.topology.ITopologyAware;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;
import org.openflow.protocol.OFPhysicalPort.OFPortState;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class sends out LLDP messages containing the sending switch's datapath
 * id as well as the outgoing port number.  Received LLDP messages that match
 * a known switch cause a new LinkTuple to be created according to the
 * invariant rules listed below.  This new LinkTuple is also passed to routing
 * if it exists to trigger updates.
 *
 * This class also handles removing links that are associated to switch ports
 * that go down, and switches that are disconnected.
 *
 * Invariants:
 *  -portLinks and switchLinks will not contain empty Sets outside of critical sections
 *  -portLinks contains LinkTuples where one of the src or dst SwitchPortTuple matches the map key
 *  -switchLinks contains LinkTuples where one of the src or dst SwitchPortTuple's id matches the switch id
 *  -Each LinkTuple will be indexed into switchLinks for both src.id and dst.id,
 *    and portLinks for each src and dst
 *  -The updates queue is only added to from within a held write lock
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class TopologyImpl implements IOFMessageListener, IOFSwitchListener, ITopology {
    protected static Logger log = LoggerFactory.getLogger(TopologyImpl.class);

    // Names of table/fields for links in the storage API
    private static final String LINK_TABLE_NAME = "controller_link";
    private static final String LINK_ID = "id";
    private static final String LINK_SRC_SWITCH = "src_switch_id";
    private static final String LINK_SRC_PORT = "src_port";
    private static final String LINK_SRC_PORT_STATE = "src_port_state";
    private static final String LINK_DST_SWITCH = "dst_switch_id";
    private static final String LINK_DST_PORT = "dst_port";
    private static final String LINK_DST_PORT_STATE = "dst_port_state";
    private static final String LINK_VALID_TIME = "valid_time";

    protected IBeaconProvider beaconProvider;
    protected IStorageSource storageSource;

    /**
     * Map from link to the most recent time it was verified functioning
     */
    protected Map<LinkTuple, LinkInfo> links;
    protected Timer lldpSendTimer;
    protected Long lldpFrequency = 15L * 1000; // sending frequency
    protected Long lldpTimeout = 35L * 1000; // timeout
    protected ReentrantReadWriteLock lock;

    /**
     * Map from a id:port to the set of links containing it as an endpoint
     */
    protected Map<SwitchPortTuple, Set<LinkTuple>> portLinks;
    
    protected volatile boolean shuttingDown = false;

    /**
     * Map from switch id to a set of all links with it as an endpoint
     */
    protected Map<IOFSwitch, Set<LinkTuple>> switchLinks;
    protected Timer timeoutLinksTimer;
    protected Set<ITopologyAware> topologyAware;
    protected BlockingQueue<Update> updates;
    protected Thread updatesThread;

    protected Map<IOFSwitch, Set<IOFSwitch>> switchClusterMap;

    public static enum UpdateOperation {ADD, UPDATE, REMOVE};

    protected class Update {
        public IOFSwitch src;
        public short srcPort;
        public int srcPortState;
        public IOFSwitch dst;
        public short dstPort;
        public int dstPortState;
        public UpdateOperation operation;

        public Update(IOFSwitch src, short srcPort, int srcPortState,
                IOFSwitch dst, short dstPort, int dstPortState, UpdateOperation operation) {
            this.src = src;
            this.srcPort = srcPort;
            this.srcPortState = srcPortState;
            this.dst = dst;
            this.dstPort = dstPort;
            this.dstPortState = dstPortState;
            this.operation = operation;
        }

        public Update(LinkTuple lt, int srcPortState, int dstPortState, UpdateOperation operation) {
            this(lt.getSrc().getSw(), lt.getSrc().getPort(), srcPortState,
                    lt.getDst().getSw(), lt.getDst().getPort(), dstPortState, operation);
        }
    }

    public TopologyImpl() {
        this.lock = new ReentrantReadWriteLock();
        this.updates = new LinkedBlockingQueue<Update>();
    }

    protected void startUp() {
        beaconProvider.addOFMessageListener(OFType.PACKET_IN, this);
        beaconProvider.addOFMessageListener(OFType.PORT_STATUS, this);
        beaconProvider.addOFSwitchListener(this);
        links = new HashMap<LinkTuple, LinkInfo>();
        portLinks = new HashMap<SwitchPortTuple, Set<LinkTuple>>();
        switchLinks = new HashMap<IOFSwitch, Set<LinkTuple>>();

        lldpSendTimer = new Timer();
        lldpSendTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendLLDPs();
            }}, 1000, lldpFrequency);

        timeoutLinksTimer = new Timer();
        timeoutLinksTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                timeoutLinks();
            }}, 1000, lldpTimeout);

        updatesThread = new Thread(new Runnable () {
            @Override
            public void run() {
                while (true) {
                    try {
                        Update update = updates.take();
                        if (topologyAware != null) {
                            for (ITopologyAware ta : topologyAware) {
                                try {
                                    switch (update.operation) {
                                        case ADD:
                                            ta.addedLink(update.src, update.srcPort, update.srcPortState,
                                                    update.dst, update.dstPort, update.dstPortState);
                                            break;
                                        case UPDATE:
                                            ta.updatedLink(update.src, update.srcPort, update.srcPortState,
                                                    update.dst, update.dstPort, update.dstPortState);
                                            break;
                                        case REMOVE:
                                            ta.removedLink(update.src, update.srcPort, update.dst, update.dstPort);
                                            break;
                                    }
                                } catch (Exception e) {
                                    log.error("Exception on callback", e);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        log.warn("Topology Updates thread interrupted", e);
                        if (shuttingDown)
                            return;
                    }
                }
            }}, "Topology Updates");
        updatesThread.start();
    }

    protected void shutDown() {
        shuttingDown = true;
        lldpSendTimer.cancel();
        beaconProvider.removeOFSwitchListener(this);
        beaconProvider.removeOFMessageListener(OFType.PACKET_IN, this);
        beaconProvider.removeOFMessageListener(OFType.PORT_STATUS, this);
        updatesThread.interrupt();
    }

    protected void sendLLDPs() {
        Ethernet ethernet = new Ethernet()
            .setSourceMACAddress(new byte[6])
            .setDestinationMACAddress("01:80:c2:00:00:0e")
            .setEtherType(Ethernet.TYPE_LLDP);

        LLDP lldp = new LLDP();
        ethernet.setPayload(lldp);
        byte[] chassisId = new byte[] {4, 0, 0, 0, 0, 0, 0}; // filled in later
        byte[] portId = new byte[] {2, 0, 0}; // filled in later
        lldp.setChassisId(new LLDPTLV().setType((byte) 1).setLength((short) 7).setValue(chassisId));
        lldp.setPortId(new LLDPTLV().setType((byte) 2).setLength((short) 3).setValue(portId));
        lldp.setTtl(new LLDPTLV().setType((byte) 3).setLength((short) 2).setValue(new byte[] {0, 0x78}));

        // OpenFlow OUI - 00-26-E1
        byte[] dpidTLVValue = new byte[] {0x0, 0x26, (byte) 0xe1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        LLDPTLV dpidTLV = new LLDPTLV().setType((byte) 127).setLength((short) 12).setValue(dpidTLVValue);
        lldp.getOptionalTLVList().add(dpidTLV);

        Map<Long, IOFSwitch> switches = beaconProvider.getSwitches();
        byte[] dpidArray = new byte[8];
        ByteBuffer dpidBB = ByteBuffer.wrap(dpidArray);
        ByteBuffer portBB = ByteBuffer.wrap(portId, 1, 2);
        for (Entry<Long, IOFSwitch> entry : switches.entrySet()) {
            Long dpid = entry.getKey();
            IOFSwitch sw = entry.getValue();
            dpidBB.putLong(dpid);

            // set the ethernet source mac to last 6 bytes of dpid
            System.arraycopy(dpidArray, 2, ethernet.getSourceMACAddress(), 0, 6);
            // set the chassis id's value to last 6 bytes of dpid
            System.arraycopy(dpidArray, 2, chassisId, 1, 6);
            // set the optional tlv to the full dpid
            System.arraycopy(dpidArray, 0, dpidTLVValue, 4, 8);
            for (OFPhysicalPort port : sw.getEnabledPorts()) {
                if (port.getPortNumber() == OFPort.OFPP_LOCAL.getValue())
                    continue;
                
                // set the portId to the outgoing port
                portBB.putShort(port.getPortNumber());

                // serialize and wrap in a packet out
                byte[] data = ethernet.serialize();
                OFPacketOut po = new OFPacketOut();
                po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
                po.setInPort(OFPort.OFPP_NONE);

                // set actions
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(new OFActionOutput(port.getPortNumber(), (short) 0));
                po.setActions(actions);
                po.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);

                // set data
                po.setLengthU(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength() + data.length);
                po.setPacketData(data);

                // send
                try {
                    sw.getOutputStream().write(po);
                } catch (IOException e) {
                    log.error("Failure sending LLDP out port {} on switch {}",
                            new Object[]{ port.getPortNumber(), sw }, e);
                }

                // rewind for next pass
                portBB.position(1);
            }

            // rewind for next pass
            dpidBB.position(0);
        }
    }

    @Override
    public String getName() {
        return "topology";
    }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg) {
        if (msg instanceof OFPacketIn)
            return handlePacketIn(sw, (OFPacketIn) msg);
        else
            return handlePortStatus(sw, (OFPortStatus)msg);
    }

    protected Command handlePacketIn(IOFSwitch sw, OFPacketIn pi) {
        Ethernet eth = new Ethernet();
        eth.deserialize(pi.getPacketData(), 0, pi.getPacketData().length);

        if (!(eth.getPayload() instanceof LLDP))
            return Command.CONTINUE;

        LLDP lldp = (LLDP) eth.getPayload();

        // If this is a malformed lldp, or not from us, exit
        if (lldp.getPortId() == null || lldp.getPortId().getLength() != 3)
            return Command.CONTINUE;

        ByteBuffer portBB = ByteBuffer.wrap(lldp.getPortId().getValue());
        portBB.position(1);
        Short remotePort = portBB.getShort();
        Long remoteDpid = 0L;
        boolean remoteDpidSet = false;

        // Verify this LLDP packet matches what we're looking for
        for (LLDPTLV lldptlv : lldp.getOptionalTLVList()) {
            if (lldptlv.getType() == 127 && lldptlv.getLength() == 12 &&
                    lldptlv.getValue()[0] == 0x0 && lldptlv.getValue()[1] == 0x26 &&
                    lldptlv.getValue()[2] == (byte)0xe1 && lldptlv.getValue()[3] == 0x0) {
                ByteBuffer dpidBB = ByteBuffer.wrap(lldptlv.getValue());
                remoteDpid = dpidBB.getLong(4);
                remoteDpidSet = true;
                break;
            }
        }

        if (!remoteDpidSet) {
            // Ignore LLDPs not generated by us
            return Command.CONTINUE;
        }

        //log.debug("Received OpenFlow LLDP packet on port {}", pi.getInPort());
        
        IOFSwitch remoteSwitch = beaconProvider.getSwitches().get(remoteDpid);

        if (remoteSwitch == null) {
            log.error("Failed to locate remote switch with DPID: {}", remoteDpid);
            return Command.STOP;
        }
        
        if (!remoteSwitch.portEnabled(remotePort)) {
            log.debug("Ignoring link with disabled source port: switch {} port {}", remoteSwitch, remotePort);
            return Command.STOP;
        }
        if (!sw.portEnabled(pi.getInPort())) {
            log.debug("Ignoring link with disabled dest port: switch {} port {}", sw, pi.getInPort());
            return Command.STOP;
        }

        OFPhysicalPort physicalPort = remoteSwitch.getPort(remotePort);
        int srcPortState = (physicalPort != null) ? physicalPort.getState() : 0;
        physicalPort = sw.getPort(remotePort);
        int dstPortState = (physicalPort != null) ? physicalPort.getState() : 0;

        // Store the time of update to this link, and push it out to routingEngine
        LinkTuple lt = new LinkTuple(new SwitchPortTuple(remoteSwitch, remotePort),
                new SwitchPortTuple(sw, pi.getInPort()));
        addOrUpdateLink(lt, srcPortState, dstPortState);

        // Consume this message
        return Command.STOP;
    }

    protected void addOrUpdateLink(LinkTuple lt, int srcPortState, int dstPortState) {
        lock.writeLock().lock();
        try {
            Integer srcPortStateObj = Integer.valueOf(srcPortState);
            Integer dstPortStateObj = Integer.valueOf(dstPortState);
            
            Long validTime = System.currentTimeMillis();
            
            LinkInfo newLinkInfo = new LinkInfo(validTime, srcPortStateObj, dstPortStateObj);
            LinkInfo oldLinkInfo = links.put(lt, newLinkInfo);
            
            UpdateOperation updateOperation = null;
            boolean linkChanged = false;
            
            if (oldLinkInfo == null) {
                // index it by switch source
                if (!switchLinks.containsKey(lt.getSrc().getSw()))
                    switchLinks.put(lt.getSrc().getSw(), new HashSet<LinkTuple>());
                switchLinks.get(lt.getSrc().getSw()).add(lt);

                // index it by switch dest
                if (!switchLinks.containsKey(lt.getDst().getSw()))
                    switchLinks.put(lt.getDst().getSw(), new HashSet<LinkTuple>());
                switchLinks.get(lt.getDst().getSw()).add(lt);

                // index both ends by switch:port
                if (!portLinks.containsKey(lt.getSrc()))
                    portLinks.put(lt.getSrc(), new HashSet<LinkTuple>());
                portLinks.get(lt.getSrc()).add(lt);

                if (!portLinks.containsKey(lt.getDst()))
                    portLinks.put(lt.getDst(), new HashSet<LinkTuple>());
                portLinks.get(lt.getDst()).add(lt);

                writeLink(lt, newLinkInfo);
                updateOperation = UpdateOperation.ADD;
                linkChanged = true;
                
                log.debug("Added link {}", lt);
            } else {
                // Only update the port states if they've changed
                if (srcPortState == oldLinkInfo.getSrcPortState().intValue())
                    srcPortStateObj = null;
                if (dstPortState == oldLinkInfo.getDstPortState().intValue())
                    dstPortStateObj = null;
                
                // Write changes to storage. This will always write the updated valid time,
                // plus the port states if they've changed (i.e. if they weren't set to
                // null in the previous block of code.
                writeLinkInfo(lt, validTime, srcPortStateObj, dstPortStateObj);
                
                // Check if either of the port states changed to see if we need to send
                // an update and recompute the clusters below.
                linkChanged = (srcPortStateObj != null) || (dstPortStateObj != null);
                
                if (linkChanged) {
                    updateOperation = UpdateOperation.UPDATE;
                    log.debug("Updated link {}", lt);
                }
            }

            if (linkChanged) {
                updates.add(new Update(lt, srcPortState, dstPortState, updateOperation));
                updateClusters();
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<IOFSwitch, Set<LinkTuple>> getSwitchLinks() {
        return this.switchLinks;
    }
    
    /**
     *
     * @param links
     */
    protected void deleteLinks(List<LinkTuple> links) {
        lock.writeLock().lock();
        try {
            for (LinkTuple lt : links) {
                this.switchLinks.get(lt.getSrc().getSw()).remove(lt);
                this.switchLinks.get(lt.getDst().getSw()).remove(lt);
                if (this.switchLinks.containsKey(lt.getSrc().getSw()) &&
                        this.switchLinks.get(lt.getSrc().getSw()).isEmpty())
                    this.switchLinks.remove(lt.getSrc().getSw());
                if (this.switchLinks.containsKey(lt.getDst().getSw()) &&
                        this.switchLinks.get(lt.getDst().getSw()).isEmpty())
                    this.switchLinks.remove(lt.getDst().getSw());

                this.portLinks.get(lt.getSrc()).remove(lt);
                if (this.portLinks.get(lt.getSrc()).isEmpty())
                    this.portLinks.remove(lt.getSrc());
                this.portLinks.get(lt.getDst()).remove(lt);
                if (this.portLinks.get(lt.getDst()).isEmpty())
                    this.portLinks.remove(lt.getDst());

                this.links.remove(lt);
                updates.add(new Update(lt, 0, 0, UpdateOperation.REMOVE));

                removeLink(lt);

                log.debug("Deleted link {}", lt);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected Command handlePortStatus(IOFSwitch sw, OFPortStatus ps) {
        log.debug("handlePortStatus: Switch {} port #{} reason {}; config is {} state is {}",
                  new Object[] {HexString.toHexString(sw.getId()),
                                ps.getDesc().getPortNumber(),
                                ps.getReason(),
                                ps.getDesc().getConfig(),
                                ps.getDesc().getState()});
        
        SwitchPortTuple tuple = new SwitchPortTuple(sw, ps.getDesc().getPortNumber());

        lock.writeLock().lock();
        try {
            boolean topologyChanged = false;
            // if ps is a delete, or a modify where the port is down or configured down
            if ((byte)OFPortReason.OFPPR_DELETE.ordinal() == ps.getReason() ||
                ((byte)OFPortReason.OFPPR_MODIFY.ordinal() == ps.getReason() && !portEnabled(ps.getDesc()))) {
    
                List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
                if (this.portLinks.containsKey(tuple)) {
                    log.debug("handlePortStatus: Switch {} port #{} reason {}; removing links",
                              new Object[] {HexString.toHexString(sw.getId()),
                                            ps.getDesc().getPortNumber(),
                                            ps.getReason()});
                    eraseList.addAll(this.portLinks.get(tuple));
                    deleteLinks(eraseList);
                    topologyChanged = true;
                }
            }
            // If ps is a port modification and the port state has changed that affects links in the topology
            else if (ps.getReason() == (byte)OFPortReason.OFPPR_MODIFY.ordinal()) {
                if (this.portLinks.containsKey(tuple)) {
                    for (LinkTuple link: this.portLinks.get(tuple)) {
                        LinkInfo linkInfo = links.get(link);
                        assert(linkInfo != null);
                        Integer updatedSrcPortState = null;
                        Integer updatedDstPortState = null;
                        if (link.getSrc().equals(tuple) && (linkInfo.getSrcPortState() != ps.getDesc().getState())) {
                            updatedSrcPortState = ps.getDesc().getState();
                            linkInfo.setSrcPortState(updatedSrcPortState);
                        }
                        if (link.getDst().equals(tuple) && (linkInfo.getDstPortState() != ps.getDesc().getState())) {
                            updatedDstPortState = ps.getDesc().getState();
                            linkInfo.setDstPortState(updatedDstPortState);
                        }
                        if ((updatedSrcPortState != null) || (updatedDstPortState != null)) {
                            writeLinkInfo(link, null, updatedSrcPortState, updatedDstPortState);
                            topologyChanged = true;
                        }
                    }
                }
            } 
            
            if (topologyChanged) {
                updateClusters();
            } else {
                log.debug("handlePortStatus: Switch {} port #{} reason {}; no links to update/remove",
                        new Object[] {HexString.toHexString(sw.getId()),
                                      ps.getDesc().getPortNumber(),
                                      ps.getReason()});
            }
        } finally {
            lock.writeLock().unlock();
        }
        return Command.CONTINUE;
    }

    @Override
    public void addedSwitch(IOFSwitch sw) {
        // no-op
    }

    @Override
    public void removedSwitch(IOFSwitch sw) {
        List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
        lock.writeLock().lock();
        try {
            if (switchLinks.containsKey(sw)) {
                // add all tuples with an endpoint on this switch to erase list
                eraseList.addAll(switchLinks.get(sw));
                deleteLinks(eraseList);
                updateClusters();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected void timeoutLinks() {
        List<LinkTuple> eraseList = new ArrayList<LinkTuple>();
        Long curTime = System.currentTimeMillis();

        // reentrant required here because deleteLink also write locks
        lock.writeLock().lock();
        try {
            Iterator<Entry<LinkTuple, LinkInfo>> it = this.links.entrySet().iterator();
            while (it.hasNext()) {
                Entry<LinkTuple, LinkInfo> entry = it.next();
                if (entry.getValue().getValidTime() + this.lldpTimeout < curTime) {
                    eraseList.add(entry.getKey());
                }
            }
    
            deleteLinks(eraseList);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private boolean portEnabled(OFPhysicalPort port) {
        if (port == null)
            return false;
        if ((OFPortConfig.OFPPC_PORT_DOWN.getValue() & port.getConfig()) > 0)
            return false;
        if ((OFPortState.OFPPS_LINK_DOWN.getValue() & port.getState()) > 0)
            return false;
        // Port STP state doesn't work with multiple VLANs, so ignore it for now
        //if ((port.getState() & OFPortState.OFPPS_STP_MASK.getValue()) == OFPortState.OFPPS_STP_BLOCK.getValue())
        //    return false;
        return true;
    }

    /**
     * @param beaconProvider the beaconProvider to set
     */
    public void setBeaconProvider(IBeaconProvider beaconProvider) {
        this.beaconProvider = beaconProvider;
    }

    @Override
    public boolean isInternal(SwitchPortTuple idPort) {
        lock.readLock().lock();
        boolean result;
        try {
            result = this.portLinks.containsKey(idPort);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    @Override
    public Map<LinkTuple, LinkInfo> getLinks() {
        lock.readLock().lock();
        Map<LinkTuple, LinkInfo> result;
        try {
            result = new HashMap<LinkTuple, LinkInfo>(links);
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    private boolean linkStpBlocked(LinkTuple tuple) {
        LinkInfo linkInfo = links.get(tuple);
        if (linkInfo == null)
            return false;
        return ((linkInfo.getSrcPortState() & OFPortState.OFPPS_STP_MASK.getValue()) == OFPortState.OFPPS_STP_BLOCK.getValue()) ||
            ((linkInfo.getDstPortState() & OFPortState.OFPPS_STP_MASK.getValue()) == OFPortState.OFPPS_STP_BLOCK.getValue());
    }
    
    private void traverseCluster(Set<LinkTuple> links, Set<IOFSwitch> cluster) {
        // NOTE: This function assumes that the caller has already acquired
        // a write lock on the "lock" data member.
        // FIXME: To handle large networks we probably should recode this to not
        // use recursion to avoid stack overflow.
        for (LinkTuple link: links) {
            // FIXME: Is this the right check for handling STP correctly?
            if (!linkStpBlocked(link)) {
                IOFSwitch dstSw = link.getDst().getSw();
                if (switchClusterMap.get(dstSw) == null) {
                    cluster.add(dstSw);
                    switchClusterMap.put(dstSw, cluster);
                    Set<LinkTuple> dstLinks = switchLinks.get(dstSw);
                    traverseCluster(dstLinks, cluster);
                }
            }
        }
    }
    
    protected void updateClusters() {
        // NOTE: This function assumes that the caller has already acquired
        // a write lock on the "lock" data member.
        
        log.debug("Updating topology cluster info");
        
        switchClusterMap = new HashMap<IOFSwitch, Set<IOFSwitch>>();
        for (Map.Entry<IOFSwitch, Set<LinkTuple>> entry: switchLinks.entrySet()) {
            IOFSwitch sw = entry.getKey();
            if (switchClusterMap.get(sw) == null) {
                Set<IOFSwitch> cluster = new HashSet<IOFSwitch>();
                cluster.add(sw);
                switchClusterMap.put(sw, cluster);
                traverseCluster(entry.getValue(), cluster);
            }
        }
    }
    
    public Set<IOFSwitch> getSwitchesInCluster(IOFSwitch sw) {
        Set<IOFSwitch> returnCluster = null;
        lock.readLock().lock();
        try {
            Set<IOFSwitch> cluster = switchClusterMap.get(sw);
            if (cluster != null) {
                returnCluster = new HashSet<IOFSwitch>(cluster);
            } else {
                returnCluster = new HashSet<IOFSwitch>();
                returnCluster.add(sw);
            }
        }
        finally {
            lock.readLock().unlock();
        }
        return returnCluster;
    }

    public boolean inSameCluster(IOFSwitch switch1, IOFSwitch switch2) {
        lock.readLock().lock();
        try {
            Set<IOFSwitch> cluster1 = switchClusterMap.get(switch1);
            Set<IOFSwitch> cluster2 = switchClusterMap.get(switch2);
            return (cluster1 != null) && (cluster1 == cluster2);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    // STORAGE METHODS
    
    void clearAllLinks() {
        IResultSet resultSet = storageSource.executeQuery(LINK_TABLE_NAME, null, null, null);
        while (resultSet.next())
            resultSet.deleteRow();
        resultSet.save();
        resultSet.close();
    }

    private String getLinkId(LinkTuple lt) {
        String srcDpid = HexString.toHexString(lt.getSrc().getSw().getId());
        String dstDpid = HexString.toHexString(lt.getDst().getSw().getId());
        return srcDpid + "-" + lt.getSrc().getPort() + "-" +
            dstDpid + "-" + lt.getDst().getPort();
    }
    
    void writeLink(LinkTuple lt, LinkInfo linkInfo) {
        Map<String, Object> rowValues = new HashMap<String, Object>();
        
        String id = getLinkId(lt);
        rowValues.put(LINK_ID, id);
        String srcDpid = HexString.toHexString(lt.getSrc().getSw().getId());
        rowValues.put(LINK_SRC_SWITCH, srcDpid);
        rowValues.put(LINK_SRC_PORT, lt.getSrc().getPort());
        rowValues.put(LINK_SRC_PORT_STATE, linkInfo.getSrcPortState());
        String dstDpid = HexString.toHexString(lt.getDst().getSw().getId());
        rowValues.put(LINK_DST_SWITCH, dstDpid);
        rowValues.put(LINK_DST_PORT, lt.getDst().getPort());
        rowValues.put(LINK_DST_PORT_STATE, linkInfo.getDstPortState());
        rowValues.put(LINK_VALID_TIME, linkInfo.getValidTime());
        storageSource.updateRow(LINK_TABLE_NAME, rowValues);
    }

    public Long readLinkValidTime(LinkTuple lt) {
        String[] columns = { LINK_VALID_TIME };
        String id = getLinkId(lt);
        IResultSet resultSet = storageSource.executeQuery(LINK_TABLE_NAME, columns,
                new OperatorPredicate(LINK_ID, OperatorPredicate.Operator.EQ, id), null);
        if (!resultSet.next())
            return null;
        Long validTime = resultSet.getLong(LINK_VALID_TIME);
        return validTime;
    }

    public void writeLinkInfo(LinkTuple lt, Long validTime, Integer srcPortState, Integer dstPortState) {
        Map<String, Object> rowValues = new HashMap<String, Object>();
        String id = getLinkId(lt);
        rowValues.put(LINK_ID, id);
        if (validTime != null)
            rowValues.put(LINK_VALID_TIME, validTime);
        if (srcPortState != null)
            rowValues.put(LINK_SRC_PORT_STATE, srcPortState);
        if (dstPortState != null)
            rowValues.put(LINK_DST_PORT_STATE, dstPortState);
        storageSource.updateRow(LINK_TABLE_NAME, id, rowValues);
    }

    /*
    private LinkTuple readDaoLinkTuple(IResultSet resultSet) {
        String srcDpidString = resultSet.getString(LINK_SRC_SWITCH);
        Long srcDpid = HexString.toLong(srcDpidString);
        Short srcPortNumber = resultSet.getShortObject(LINK_SRC_PORT);
        Integer srcPortState = resultSet.getIntegerObject(LINK_SRC_PORT_STATE);
        String dstDpidString = resultSet.getString(LINK_DST_SWITCH);
        Long dstDpid = HexString.toLong(dstDpidString);
        Short dstPortNumber = resultSet.getShort(LINK_DST_PORT);
        Integer dstPortState = resultSet.getIntegerObject(LINK_DST_PORT_STATE);
        DaoLinkTuple lt = new DaoLinkTuple(srcDpid, srcPortNumber, srcPortState,
                dstDpid, dstPortNumber, dstPortState);
        return lt;
    }
    
    public Set<DaoLinkTuple> getLinks(Long id) {
        Set<DaoLinkTuple> results = new HashSet<DaoLinkTuple>();
        String idString = HexString.toHexString(id);
        IResultSet resultSet = storageSource.executeQuery(LINK_TABLE_NAME, null,
                new CompoundPredicate(CompoundPredicate.Operator.OR, false,
                        new OperatorPredicate(LINK_SRC_SWITCH, OperatorPredicate.Operator.EQ, idString),
                        new OperatorPredicate(LINK_DST_SWITCH, OperatorPredicate.Operator.EQ, idString)), null);
        for (IResultSet nextResult : resultSet) {
            DaoLinkTuple lt = readDaoLinkTuple(nextResult);
            results.add(lt);
        }
        return results.size() > 0 ? results : null;
    }

    public Set<DaoLinkTuple> getLinks(DaoSwitchPortTuple idPort) {
        Set<DaoLinkTuple> results = new HashSet<DaoLinkTuple>();
        String idString = HexString.toHexString(idPort.getId());
        IResultSet resultSet = storageSource.executeQuery(LINK_TABLE_NAME, null,
                new CompoundPredicate(CompoundPredicate.Operator.OR, false,
                        new CompoundPredicate(CompoundPredicate.Operator.AND, false,
                                new OperatorPredicate(LINK_SRC_SWITCH, OperatorPredicate.Operator.EQ, idString),
                                new OperatorPredicate(LINK_SRC_PORT, OperatorPredicate.Operator.EQ, idPort.getPort())),
                        new CompoundPredicate(CompoundPredicate.Operator.AND, false,
                                new OperatorPredicate(LINK_DST_SWITCH, OperatorPredicate.Operator.EQ, idString),
                                new OperatorPredicate(LINK_DST_PORT, OperatorPredicate.Operator.EQ, idPort.getPort()))), null);
        for (IResultSet nextResult : resultSet) {            
            DaoLinkTuple lt = readDaoLinkTuple(nextResult);
            results.add(lt);
        }
        return results.size() > 0 ? results : null;
    }

    public Set<DaoLinkTuple> getLinksToExpire(Long deadline) {
        Set<DaoLinkTuple> results = new HashSet<DaoLinkTuple>();
        IResultSet resultSet = storageSource.executeQuery(LINK_TABLE_NAME, null,
                new OperatorPredicate(LINK_VALID_TIME, OperatorPredicate.Operator.LTE, deadline), null);
        for (IResultSet nextResult : resultSet) {
            DaoLinkTuple lt = readDaoLinkTuple(nextResult);
            results.add(lt);
        }
        return results.size() > 0 ? results : null;
    }
    */
    
    void removeLink(LinkTuple lt) {
        String id = getLinkId(lt);
        storageSource.deleteRow(LINK_TABLE_NAME, id);
    }

    /*
    Set<DaoLinkTuple> removeLinksBySwitch(Long id) {
        Set<DaoLinkTuple> deleteSet = getLinks(id);
        if (deleteSet != null) {
            for (DaoLinkTuple lt : deleteSet) {
                removeLink(lt);
            }
        }
        return deleteSet;
    }

    public Set<DaoLinkTuple> removeLinksBySwitchPort(DaoSwitchPortTuple idPort) {
        Set<DaoLinkTuple> deleteSet = getLinks(idPort);
        if (deleteSet != null) {
            for (DaoLinkTuple lt : deleteSet) {
                removeLink(lt);
            }
        }
        return deleteSet;
    }
    */
    
    /**
     * @param topologyAware the topologyAware to set
     */
    public void setTopologyAware(Set<ITopologyAware> topologyAware) {
        // TODO make this a copy on write set or lock it somehow
        this.topologyAware = topologyAware;
    }

    /**
     * @param storageSource the storage source to use for persisting link info
     */
    public void setStorageSource(IStorageSource storageSource) {
        this.storageSource = storageSource;
        storageSource.createTable(LINK_TABLE_NAME);
        storageSource.setTablePrimaryKeyName(LINK_TABLE_NAME, LINK_ID);
    }

}
