/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package as.pa2.loadbalancer;

import as.pa2.gui.MonitorLBGUI;
import as.pa2.loadbalancer.strategies.IFRule;
import as.pa2.loadbalancer.strategies.RoundRobinRule;
import as.pa2.monitor.Monitor;
import as.pa2.monitor.availability.ParallelHeartBeat;
import as.pa2.monitor.availability.ParallelHeartBeatStategy;
import as.pa2.monitor.availability.SerialHeartBeat;
import as.pa2.monitor.availability.SerialHeartBeatStrategy;
import as.pa2.protocol.PiRequest;
import as.pa2.protocol.PiResponse;
import as.pa2.server.Server;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load-Balancer Implementation.
 *
 * @author Bruno Assunção 89010
 * @author Hugo Chaves  90842
 * 
 */

public class LoadBalancer implements IFLoadBalancer, Runnable{
    
    private static final boolean TEST = false;
    private final static IFRule DEFAULT_RULE = new RoundRobinRule();
    private static final String DEFAULT_NAME = "lb-default";
    private static final String PREFIX = "load-balancer_";
    
    protected IFRule rule = DEFAULT_RULE;
    
    protected String name = DEFAULT_NAME;
    
    protected Monitor monitor;
    
    protected String ip;
    protected int port;
    protected ServerSocket socket;
    
    protected boolean isStopped;
    
    protected ExecutorService clientConnnectionsPool;
    protected ExecutorService serverConnectionsPool;
    
    protected LinkedBlockingQueue<PiRequest> requestQueue = new LinkedBlockingQueue<PiRequest>();
    protected ConcurrentHashMap<Integer,ClientConnection> clientConnections = new ConcurrentHashMap<Integer, ClientConnection>();
    protected ConcurrentHashMap<Server,Socket> serverConnections = new ConcurrentHashMap<Server,Socket>();
    protected ConcurrentHashMap<PiRequest,PiResponse> handledRequests = new ConcurrentHashMap<PiRequest,PiResponse>();
    
    protected MonitorLBGUI gui;
    protected String monitorIp;
    protected int monitorPort;
    
    protected Thread clientConnectionsThread;
    protected Thread monitorThread;
    
    public LoadBalancer(MonitorLBGUI gui) {
        this.gui = gui;
        this.isStopped = false;
        setRule(DEFAULT_RULE);
        this.clientConnnectionsPool = Executors.newFixedThreadPool(10);
        this.serverConnectionsPool = Executors.newFixedThreadPool(10);

    }
    
    public LoadBalancer(String ip, int port, String monitorIp, int monitorPort) {
        this.name = DEFAULT_NAME;
        this.isStopped = false;
        setRule(DEFAULT_RULE);
        this.ip = ip;
        this.port = port;
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
        this.clientConnnectionsPool = Executors.newFixedThreadPool(10);
        this.serverConnectionsPool = Executors.newFixedThreadPool(10);
    }
    
    public LoadBalancer(String name, IFRule rule) {
        this.name = name;
        this.isStopped = false;
        setRule(rule);    
        this.clientConnnectionsPool = Executors.newFixedThreadPool(10);
        this.serverConnectionsPool = Executors.newFixedThreadPool(10);
    }
     
    @Override
    public Server chooseServer(Object key) {
        if (rule == null) {
            return null;
        } else {
            try {
                return rule.choose(key);
            } catch (Exception e) {
                updateLogs("LoadBalancer " + name + ": Error choosing server for " + key);
                return null;
            }
        }
    }

    public String choose(Object key) {
        if (rule == null) {
            return null;
        } else {
            try {
                Server server = rule.choose(key);
                return ((server == null) ? null : server.getId());
            } catch (Exception e) {
                updateLogs("LoadBalancer " + name + ": Error choosing server for " + key);
                return null;
            }
        }
    }

    
    
    @Override
    public List<Server> getReachableServers() {
        return monitor.getReachableServers();
    }

    @Override
    public List<Server> getAllServers() {
        return monitor.getReachableServers();
    }
    
    
    public IFRule getRule() {
        return rule;
    }
    
    public void setRule(IFRule rule) {
        if (rule != null) {
            this.rule = rule;
        } else {
            this.rule = new RoundRobinRule();
        }
        if (this.rule.getLoadBalancer() != this) {
            this.rule.setLoadBalancer(this);
        }
    }
    
    public String getName() {
        return name;
    }
    
    //TODO try this
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{LoadBalancer:name=")
                .append(this.getName())
    //            .append(", current list of servers=").append(this.allServersList)
                .append("}");
        return sb.toString();
    }

    @Override
    public void run() {
        openClientsSocket();
        updateLogs("LoadBalancer Started!");
        updateDebugLogs("LoadBalancer Started!");
        
        //monitor = new Monitor(monitorIp, monitorPort, null, null);
        monitor = new Monitor(monitorIp, monitorPort, new SerialHeartBeat(), new SerialHeartBeatStrategy());
        //monitor = new Monitor(monitorIp, monitorPort, new ParallelHeartBeat(), new ParallelHeartBeatStategy());
        if(gui != null)
            monitor.setMonitorLBGui(this.gui);
        
        //(new Thread(monitor)).start();
        clientConnnectionsPool.execute(monitor);
        clientConnnectionsPool.execute(new HandleClientConnections());
        int clientCount = 0;
        //clientConnectionsThread = new Thread(new HandleClientConnections());
        //clientConnectionsThread.start();
        
        while (!isStopped()) {
            try {
                /* choose and handle server connections */
                if (!requestQueue.isEmpty() && !getReachableServers().isEmpty()) {
                    Server choosenServer = chooseServer(this);
                    updateLogs("LoadBalancer received request [" + requestQueue.peek().toString() + "]");
                    updateDebugLogs("LoadBalancer received request [" + requestQueue.peek().toString() + "]");
                    updateLogs("LoadBalancer choosen server " + choosenServer.getHost());
                    updateDebugLogs("LoadBalancer choosen server " + choosenServer.getHost());
                    Socket serverSocket = null;
                    try {
                        serverSocket = new Socket(choosenServer.getHost(),choosenServer.getPort());
                        serverConnections.put(choosenServer, serverSocket);
                        //System.out.println("Created new server socket!");
                        this.serverConnectionsPool.execute(new ServerConnection(clientConnections, handledRequests, requestQueue,serverConnections.get(choosenServer), choosenServer.getId(), requestQueue.take()));
                    } catch (IOException ex) {
                        //erverConnections.clear();
                        //monitor.markServerDown(choosenServer);                        
                    }        
                }
            } catch (InterruptedException ex) {
                updateLogs("[!] LoadBalancer: Failed to take request from queue.");
            }
        }
        this.clientConnnectionsPool.shutdownNow();
        this.serverConnectionsPool.shutdownNow();
        updateDebugLogs("Shutting down pools ...");
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMonitorIp() {
        return monitorIp;
    }

    public void setMonitorIp(String monitorIp) {
        this.monitorIp = monitorIp;
    }

    public int getMonitorPort() {
        return monitorPort;
    }

    public void setMonitorPort(int monitorPort) {
        this.monitorPort = monitorPort;
    }
    
    private synchronized boolean isStopped() {
        return this.isStopped;
    }
    
    public synchronized void stop() {
        this.isStopped = true;
        try {
            updateLogs("Load Balancer Stopped!");
            this.socket.close();
            this.clientConnectionsThread.interrupt();
            this.monitor.shutdown();
        } catch (IOException ioe) {
            throw new RuntimeException("Error closing LoadBalancer",ioe);
        }
    }
    
    private void openClientsSocket() {
        try {
            this.socket = new ServerSocket(this.port, 100, InetAddress.getByName(this.ip));
        } catch (IOException ioe) {
            throw new RuntimeException(
                    "Cannot open port "+this.port+"!", ioe);
        }
    }
    
    private class HandleClientConnections implements Runnable {

        @Override
        public void run() {
            //for secure reasons choose random number to start count
        int clientCount = 3;
        Socket clientSocket = null;
            while ( true ) {
                try {
                    /* handle client connections */ 
                    clientSocket = socket.accept();
                    updateLogs("LoadBalancer recieved a new client connection.");
                    updateDebugLogs("LoadBalancer recieved a new client connection.");
                    ClientConnection newConnection = new ClientConnection(requestQueue, clientConnections,clientSocket, clientCount, gui);
                    //clientConnections.put(clientCount, newConnection);
                    //clientCount++;
                    clientConnnectionsPool.execute(newConnection);
                } catch (IOException ex) {
                    //Logger.getLogger(LoadBalancer.class.getName()).log(Level.SEVERE, null, ex);
                    break;
                }
            }
            try {
                if(clientSocket!=null){
                    clientSocket.close();
                }
            } catch (IOException ex) {
                updateDebugLogs("Fail to close Client Socket.");
            }
        }
    }
        
    private void updateDebugLogs(String s) {
        if (TEST) {
            System.out.println(s);
        }
    }
    
    private void updateLogs(String s) {
        if (gui != null) {
            gui.updateLogs(s);
        }
    }
    
}
