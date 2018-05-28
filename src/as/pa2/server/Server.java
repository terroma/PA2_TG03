/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package as.pa2.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 * @author bruno
 */
public class Server implements Runnable {

    protected int serverPort;
    protected ServerSocket serverSocket;
    protected boolean isStopped;
    protected Thread runningThread;
    protected ExecutorService threadPool;
    
    protected int serverId;
    protected UUID uniqueClientId = UUID.randomUUID();
    
    private String host;
    private volatile String id;
    private volatile boolean isAliveFlag;
    private volatile boolean readyToServe = true;
    
    /* default server constructor */
    public Server() {
        this.serverId = uniqueClientId.hashCode();
        System.out.println("[*] Starting Server["+serverId+"] ...");
        this.serverPort = 8080;
        this.serverSocket = null;
        this.isStopped = false;
        this.runningThread = null;
        this.threadPool = Executors.newFixedThreadPool(10);
        this.host = "127.0.0.1"; // or locahost
    }
    
    public Server(String host, int port, String monitorIP, int monitorPort, int loadBalancerPort, int queueSize) {
        this.host = host;
        this.serverPort = port;
        this.id = host + ":" + port;
        isAliveFlag = false;
        this.serverSocket = null;
        this.isStopped = false;
        this.runningThread = null;
        this.threadPool = Executors.newFixedThreadPool(10);
        System.out.println(host + " : " + port);
        System.out.println("[*] Starting Server["+id+"] ...");
    }
    
    public Server(String id) {
        setId(id);
        isAliveFlag = false;
    }
    
    @Override
    public void run() {
        synchronized( this ) {
            this.runningThread = Thread.currentThread();
        }
        openServerSocket();
        System.out.println("[*] Server["+id+"] Connected ...");
        
        while (!isStopped()) {
            Socket clientSocket = null;
            
            try {
                clientSocket = this.serverSocket.accept();
                System.out.println("[*] Server["+serverId+"] "
                    + "Accepted Connection: "+clientSocket.getInetAddress().getHostAddress()
                        +":"+clientSocket.getPort());
                this.threadPool.execute(new RequestHandler(clientSocket, this.serverId));
            } catch (IOException ioe) {
                if (isStopped()) {
                    System.out.println("Server Stopped.");
                    break;
                }
                throw new RuntimeException(
                        "Error accepting client connection.",ioe);
            }
            /*
            this.threadPool.execute(
                        new RequestHandler(clientSocket, this.serverId));
            */
        }
        this.threadPool.shutdown();
        this.stop();
        System.out.println("Server Stopped.");
    }
    
    private synchronized boolean isStopped() {
        return this.isStopped;
    }
    
    public synchronized void stop() {
        this.isStopped = true;
        try {
            System.out.println("Server Stoped!");
            this.serverSocket.close();
            
        } catch (IOException ioe) {
            throw new RuntimeException("Error closing server",ioe);
        }
    }
    
    private void openServerSocket() {
        try {
            this.serverSocket = new ServerSocket(this.serverPort, 100, InetAddress.getByName(this.host));
        } catch (IOException ioe) {
            throw new RuntimeException(
                    "Cannot open port "+this.serverPort+"!", ioe);
        }
    }
    
    public void setAlive(boolean isAliveFlag) {
        this.isAliveFlag = isAliveFlag;
    }
    
    public boolean isAlive() {
        return isAliveFlag;
    }
    
    public String getId() {
        return id;
    }
    
    /* hostPort combination */
    public void setId(String id) {
        this.id = id;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        if (host != null) {
            this.host = host;
            id = host + ":" + serverPort;
        }
    }
    
    public int getPort() {
        return serverPort;
    }
    
    public void setPort(int port) {
        this.serverPort = port;
        
        if (host != null) {
            id = host + ":" + port;
        }
    }
    
    public String getHostPort() {
        return host + ":" + serverPort;
    }
    
    public final boolean isReadyToServe() {
        return readyToServe;
    }
    
    public final void setReadyToServe(boolean readyToServe) {
        this.readyToServe = readyToServe;
    }
    
    @Override
    public String toString() {
        return this.getId();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof Server))
            return false;
        Server svc = (Server) obj;
        return svc.getId().equals(this.getId());
    }
    
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (null == this.getId() ? 0 : this.getId().hashCode());
        return hash;
    }
    
    public static void main(String[] args) {
        Server s = new Server("127.0.0.1", 5000, "", 0,0,0);
        s.run();
    }
}
