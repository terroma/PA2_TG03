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
import as.pa2.monitor.availability.ParallelPing;
import as.pa2.monitor.availability.ParallelPingStategy;
import as.pa2.monitor.availability.SerialPing;
import as.pa2.monitor.availability.SerialPingStrategy;
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
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load-Balancer Implementation.
 *
 * @author terroma
 */
public class LoadBalancer implements IFLoadBalancer, Runnable{
    
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
    
    public LoadBalancer(MonitorLBGUI gui) {
        this.gui = gui;
        this.isStopped = false;
        setRule(DEFAULT_RULE);
        this.clientConnnectionsPool = Executors.newFixedThreadPool(4);
        this.serverConnectionsPool = Executors.newFixedThreadPool(4);

    }
    
    public LoadBalancer(String ip, int port, String monitorIp, int monitorPort) {
        this.name = DEFAULT_NAME;
        this.isStopped = false;
        setRule(DEFAULT_RULE);
        this.ip = ip;
        this.port = port;
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
        this.clientConnnectionsPool = Executors.newFixedThreadPool(4);
        this.serverConnectionsPool = Executors.newFixedThreadPool(4);
    }
    
    public LoadBalancer(String name, IFRule rule) {
        this.name = name;
        this.isStopped = false;
        setRule(rule);    
        this.clientConnnectionsPool = Executors.newFixedThreadPool(4);
        this.serverConnectionsPool = Executors.newFixedThreadPool(4);
    }
     
    @Override
    public Server chooseServer(Object key) {
        if (rule == null) {
            return null;
        } else {
            try {
                return rule.choose(key);
            } catch (Exception e) {
                System.out.printf("LoadBalancer [{}]: Error choosing server for "
                        + "key {}", name, key, e);
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
                System.out.printf("LoadBalancer [{}]: Error choosing server for "
                        + "key {}", name, key, e);
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
        System.out.println("[*] LoadBalancer Started ...");
        
        monitor = new Monitor(monitorIp, monitorPort, new SerialPing(), new SerialPingStrategy());
        //monitor = new Monitor(monitorIp, monitorPort, new ParallelPing(), new ParallelPingStategy());
        monitor.setMonitorLBGui(this.gui);
        (new Thread(monitor)).start();
        
        int clientCount = 0;
        (new Thread(new HandleClientConnections())).start();
        while (!isStopped()) {
            /* handle client connections */
            //Socket clientSocket = null;
            try {
                //clientSocket = this.socket.accept();
                //System.out.println("[*] LoadBalancer recieved new client Connection.");
                //ClientConnection newConnection = new ClientConnection(requestQueue, clientSocket, clientCount++);
                //clientConnections.put(clientCount, newConnection);
                //this.clientConnnectionsPool.execute(newConnection);
                
                /* choose and handle server connections */
                if (!requestQueue.isEmpty()) {
                    Server choosenServer = chooseServer(this);
                    System.out.println("[*] LoadBalancer: choosen server "+choosenServer.getId());
                    if (!serverConnections.containsKey(choosenServer) || !serverConnections.isEmpty()) {
                        Socket serverSocket = new Socket(choosenServer.getHost(),choosenServer.getPort());
                        serverConnections.put(choosenServer, serverSocket);
                    }
                    //ServerConnection sc = new ServerConnection(clientConnections, handledRequests, serverConnections.get(choosenServer), choosenServer.getId(), requestQueue.take());
                    this.serverConnectionsPool.execute(new ServerConnection(clientConnections, handledRequests, serverConnections.get(choosenServer), choosenServer.getId(), requestQueue.take()));   
                    System.out.println("Submitted to threadpool");
                }
                //System.out.println("Exit");
            } catch (IOException ioe) {
                if (isStopped()) {
                    System.out.println("[*] LoabBalancer Stopped!");
                    break;
                }
                throw new RuntimeException("[!] LoadBalancer: Error accepting connections from clients.",ioe);
            } catch (InterruptedException ex) {
                System.out.println("[!] LoadBalancer: Failed to take request from queue ...");
            }
        }
        this.clientConnnectionsPool.shutdownNow();
        this.serverConnectionsPool.shutdownNow();
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
            System.out.println("LoabBalancer Stopped!");
            this.socket.close();
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
            while ( true ) {
                try {
                    /* handle client connections */
                    Socket clientSocket = null;
                    clientSocket = socket.accept();
                    System.out.println("[*] LoadBalancer recieved new client Connection.");
                    ClientConnection newConnection = new ClientConnection(requestQueue, clientSocket, clientCount);
                    clientConnections.put(clientCount, newConnection);
                    clientCount++;
                    clientConnnectionsPool.execute(newConnection);
                } catch (IOException ex) {
                    Logger.getLogger(LoadBalancer.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
    }
        
    public static void main(String[] args) {
        LoadBalancer lb = new LoadBalancer("127.0.0.1",5000,"127.0.0.2",5000);
        lb.run();
    }
    
}
