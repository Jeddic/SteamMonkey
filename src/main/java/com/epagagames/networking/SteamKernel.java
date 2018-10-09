/*
 * Copyright (c) 2009-2012 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.epagagames.networking;

import com.codedisaster.steamworks.*;
import com.jme3.network.Filter;
import com.jme3.network.HostedConnection;
import com.jme3.network.kernel.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SteamKernel extends AbstractKernel {

  static Logger log = Logger.getLogger(SteamKernel.class.getSimpleName());

  //private static final int defaultChannel = 0;
  private int channel;

  private HostThread thread;
  //private ExecutorService writer;
  private SteamNetworking networking;
  private boolean reliable;
  private SteamServer server;
  private boolean dedicated;

  //private Map<Integer, SteamID> remoteUserIDs = new ConcurrentHashMap<Integer, SteamID>();
  //private Map<Integer, SteamID> remoteUserIDs = new ConcurrentHashMap<Integer, SteamID>();
  private Map<Integer, SteamEndpoint> endpoints = new ConcurrentHashMap<>();
  private Map<Integer, Long> lastEndpointTime = new ConcurrentHashMap<>();

  public SteamKernel(SteamServer server, int channel, boolean reliable, boolean dedicated) {
    //this.portSecondary = portSec;
    this.server = server;
    this.channel = channel;
    this.reliable = reliable;
    this.dedicated = dedicated;
  }

  @Override
  public void initialize() {

    // we use multiple copies of this class so lets not throw a fit here
    if( thread != null )
      return;

    if (dedicated) {
      networking = new SteamGameServerNetworking(peer2peerCallback);
    } else {
      networking = new SteamNetworking(peer2peerCallback);
    }
    networking.allowP2PPacketRelay(true);

    //writer = Executors.newFixedThreadPool(2, new NamedThreadFactory(toString() + "-writer"));

    thread = createHostThread();


    try {
      thread.connect();
      thread.start();
      log.log(Level.SEVERE, "Steam Kernel Started chan=" + channel);
    } catch( IOException e ) {
      throw new KernelException( "Error hosting steam networking on:" + channel, e );
    }
  }

  @Override
  public void terminate() throws InterruptedException {
    // we use multiple copies of this class so lets not throw a fit here
    if( thread == null )
      return;

    try {
      thread.close();
      //writer.shutdown();
      thread = null;

      networking.dispose();

      // Need to let any caller waiting for a read() wakeup
      wakeupReader();
    } catch( IOException e ) {
      throw new KernelException( "Error closing host connection: " + channel, e );
    }
  }

  @Override
  public void broadcast(Filter<? super Endpoint> filter, ByteBuffer data, boolean reliable, boolean copy) {
    if( copy ) {
      // Copy the data just once
//      byte[] temp = new byte[data.remaining()];
//      System.arraycopy(data.array(), data.position(), temp, 0, data.remaining());
//      data = ByteBuffer.wrap(temp);
//      data.limit(data.limit());
    }

    // Hand it to all of the endpoints that match our routing
    for( SteamEndpoint p : endpoints.values() ) {
      // Does it match the filter?
      if( filter != null && !filter.apply(p) )
        continue;

      // Send the data
      p.send( data,
          reliable ? SteamNetworking.P2PSend.Reliable : SteamNetworking.P2PSend.UnreliableNoDelay,
          channel
      );
    }
  }

  public int getChannel() {
    return channel;
  }

  public boolean isReliable() {
    return reliable;
  }

  protected HostThread createHostThread()
  {
    return new HostThread();
  }

  private void registerRemoteSteamID(SteamID steamIDUser) {
    if (!endpoints.containsKey(steamIDUser.getAccountID())) {

      SteamEndpoint p = new SteamEndpoint( nextEndpointId(), steamIDUser, this, networking );
      endpoints.put( steamIDUser.getAccountID(), p );

      // Add an event for it.
      addEvent( EndpointEvent.createAdd( this, p ) );
    }
  }

  private void unregisterRemoteSteamID(SteamID steamIDUser) {
    SteamEndpoint ep = endpoints.remove(steamIDUser.getAccountID());
    if (ep != null) {
      try {
        closeEndpoint(ep);
      } catch (IOException e) {
        log.log(Level.SEVERE, "Error Closing Steam Endpoint: ", e);
      }
    }
  }


  protected Endpoint getEndpoint( SteamID user, boolean create )
  {
    SteamEndpoint p = this.endpoints.get(user.getAccountID());
    if( p == null && create ) {
      registerRemoteSteamID(user);

      // should be populated now
      p = this.endpoints.get(user.getAccountID());
    }
    return p;
  }

  /**
   *  Called by the endpoints when they need to be closed.
   */
  protected void closeEndpoint( SteamEndpoint p ) throws IOException
  {
    // Just book-keeping to do here.
    if( endpoints.remove( p.getSteamUserID() ) == null )
      return;

    log.log( Level.FINE, "Closing endpoint:{0}.", p );
    log.log( Level.FINE, "Socket endpoints size:{0}", endpoints.size() );

    addEvent( EndpointEvent.createRemove( this, p ) );

    wakeupReader();
  }

  protected void newData(byte[] data, SteamID user)
  {
    // So the tricky part here is figuring out the endpoint and
    // whether it's new or not.  In these UDP schemes, firewalls have
    // to be ported back to a specific machine so we will consider
    // the address + port (ie: SocketAddress) the defacto unique
    // ID.
    Endpoint p = getEndpoint(user, true );

    Envelope env = new Envelope( p, data, false);
    addEnvelope( env );
  }

  private SteamNetworkingCallback peer2peerCallback = new SteamNetworkingCallback() {
    @Override
    public void onP2PSessionConnectFail(SteamID steamIDRemote, SteamNetworking.P2PSessionError sessionError) {
      log.log(Level.SEVERE, "P2P connection failed: userID=" + steamIDRemote.getAccountID() +
          ", error: " + sessionError);
      networking.closeP2PSessionWithUser(steamIDRemote);
      unregisterRemoteSteamID(steamIDRemote);
    }

    @Override
    public void onP2PSessionRequest(SteamID steamIDRemote) {
      networking.acceptP2PSessionWithUser(steamIDRemote);
      log.log(Level.INFO, "P2P connection requested by userID " + steamIDRemote.getAccountID());
      registerRemoteSteamID(steamIDRemote);
    }
  };

//
//  protected class MessageWriter implements Runnable
//  {
//    private Endpoint endpoint;
//    private DatagramPacket packet;
//    private static final int sendBufferCapacity = 65535;
//    private ByteBuffer packetSendBuffer = ByteBuffer.allocateDirect(sendBufferCapacity);
//
//    public MessageWriter( Endpoint endpoint, DatagramPacket packet )
//    {
//      this.endpoint = endpoint;
//      this.packet = packet;
//    }
//
//    public void run()
//    {
//      // Not guaranteed to always work but an extra datagram
//      // to a dead connection isn't so big of a deal.
//      if( !endpoint.isConnected() ) {
//        return;
//      }
//
//      try {
//        packetSendBuffer.clear();
//        packetSendBuffer.put(packet.getData());
//
//        if (endpoint instanceof SteamEndpoint) {
//          ((SteamEndpoint) endpoint).send(packetSendBuffer, );
//        } else {
//          endpoint.send(packetSendBuffer);
//
//        }
//
//      } catch( Exception e ) {
//        KernelException exc = new KernelException( "Error sending datagram to:" + address, e );
//        exc.fillInStackTrace();
//        reportError(exc);
//      }
//    }
//  }

  protected class HostThread extends Thread
  {
    private ByteBuffer packetReadBuffer = ByteBuffer.allocateDirect(readBufferCapacity);

    private static final int readBufferCapacity = 65535;
    private long lastDisconnectCheckTime = 0;
    private AtomicBoolean go = new AtomicBoolean(true);

    public HostThread()
    {
      setName( "Steam Thread Host@" + channel );
      setDaemon(true);
    }
//
//    protected DatagramSocket getSocket()
//    {
//      return socket;
//    }

    public void connect() throws IOException
    {
      //socket = new DatagramSocket( address );
      log.log( Level.FINE, "Hosting Steam connection:{0}.", channel );
    }

    public void close() throws IOException, InterruptedException
    {
      // Set the thread to stop
      go.set(false);

      // Make sure the channel is closed
      // do we need to do something with steam networking here?

      // And wait for it
      join();
    }

    public void run()
    {
      log.log( Level.FINE, "Kernel started for channel:{0}.", channel );

      // An atomic is safest and costs almost nothing
      while( go.get() ) {
        try {
          // Could reuse the packet but I don't see the
          // point and it may lead to subtle bugs if not properly
          // reset.
          processUpdate();

          // check for connection disconnect
          if (channel == 0 && System.currentTimeMillis() - lastDisconnectCheckTime > 5000) {
            lastDisconnectCheckTime = System.currentTimeMillis();
            lastEndpointTime.forEach((Integer key, Long val) -> {
              if (System.currentTimeMillis() - val > 60000) {
                lastEndpointTime.put(key, System.currentTimeMillis());
                SteamEndpoint ep = endpoints.get(key);
                SteamNetworking.P2PSessionState state = new SteamNetworking.P2PSessionState();
                SteamID id = SteamID.createFromNativeHandle(key);
                networking.getP2PSessionState(id, state);

                if (!state.isConnectionActive() && ep != null) {
                  HostedConnection hc = server.getConnection(ep);
                  if (hc != null) {
                    hc.close("Timed out");
                  }
                  log.log(Level.SEVERE, "Steam user timed out user={0}", id.getAccountID());
                }
              }
            });
          }
        } catch(Exception e ) {
          if( !go.get() )
            return;
          reportError( e );
        }
      }
    }

    protected void processUpdate() throws SteamException {
      processChannel(channel);

    }

    private void processChannel(int currentChannel) throws SteamException {
      int packetSize = networking.isP2PPacketAvailable(currentChannel);
      if (packetSize > 0) {

        SteamID steamIDSender = new SteamID();

        packetReadBuffer.clear();

        while ((packetSize = networking.readP2PPacket(steamIDSender, packetReadBuffer, currentChannel)) > 0) {

          // register, if unknown
          registerRemoteSteamID(steamIDSender);

          lastEndpointTime.put(steamIDSender.getAccountID(), System.currentTimeMillis());

          //int bytesReceived = packetReadBuffer.limit();
          log.log(Level.FINE,"Rcv packet: userID={0}, chan={1}, {2} bytes",
              new Object[]{ steamIDSender.getAccountID(), channel, packetSize});

          byte[] bytes = new byte[packetSize];
          for (int i=0; i < packetSize; i++) {
            bytes[i] = packetReadBuffer.get(i);
          }

          newData(bytes, steamIDSender);
          packetReadBuffer.clear();
        }

      }
    }
  }
}
