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

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworking.P2PSessionState;
import com.codedisaster.steamworks.SteamNetworkingCallback;
import com.jme3.network.kernel.Connector;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SteamConnector
 * @author Greg Hoffman
 */
public class SteamConnector implements Connector {

  private static final Logger LOG = Logger.getLogger(SteamConnector.class.getSimpleName());
  
	//private static final int defaultChannel = 0;
	private boolean reliable;
  
	private SteamNetworking networking;
  private SteamID remoteID;
  private int channel;
  private AtomicBoolean started = new AtomicBoolean(false);
  //private AtomicBoolean connected = new AtomicBoolean(false);
	private final ByteBuffer packetReadBuffer = ByteBuffer.allocateDirect(65535);
  
  private SteamNetworkingCallback peer2peerCallback = new SteamNetworkingCallback() {
		@Override
		public void onP2PSessionConnectFail(SteamID steamIDRemote, SteamNetworking.P2PSessionError sessionError) {
      LOG.log(Level.SEVERE,"P2P Client connection failed: userID=" + steamIDRemote.getAccountID() +
					", error: " + sessionError);
      //connected.set(false);

			//unregisterRemoteSteamID(steamIDRemote);
		}

		@Override
		public void onP2PSessionRequest(SteamID steamIDRemote) {
      LOG.log(Level.INFO,"P2P Client connection requested by userID " + steamIDRemote.getAccountID());
			//registerRemoteSteamID(steamIDRemote);
      if (started.get()) {
        networking.acceptP2PSessionWithUser(steamIDRemote);
      }
      //remoteID = steamIDRemote;
      //connected.set(true);

		}
	};

  public SteamConnector(SteamID id, int channel, boolean reliable) {
    remoteID = id;
    this.reliable = reliable;
		networking = new SteamNetworking(peer2peerCallback);
    //networking.acceptP2PSessionWithUser(id);
    this.channel = channel;
    started.set(true);
//		networking.
//    remoteID = id;
		//networking.allowP2PPacketRelay(true);
  }
  
  @Override
  public boolean isConnected() {
    P2PSessionState state = new P2PSessionState();
    networking.getP2PSessionState(remoteID, state);
    return state.isConnectionActive() && started.get();
  }

  @Override
  public void close() {
    networking.closeP2PSessionWithUser(remoteID);
    networking.dispose();
    started.set(false);
  }

  @Override
  public boolean available() {
    int packetSize = networking.isP2PPacketAvailable(channel);
    return packetSize > 0;
  }

  private int getActualChannel() {
    if (channel == 0) return 2;
    if (channel == 1) return 3;
    return channel;
  }

  @Override
  public ByteBuffer read() {
    P2PSessionState state = new P2PSessionState();
    networking.getP2PSessionState(remoteID, state);
    while (started.get()) {
      int actualChannel = getActualChannel();
      int packetSize = networking.isP2PPacketAvailable(actualChannel);
      if (packetSize > 0) {
        LOG.log(Level.FINER,
            "Steam Connector Read user={0}, chan={1}, {2} bytes",
            new Object[]{remoteID.getAccountID(), channel, packetSize});
        try {
          SteamID steamIDSender = new SteamID();

          packetReadBuffer.clear();

          if ((packetSize = networking.readP2PPacket(steamIDSender, packetReadBuffer, actualChannel)) > 0) {
            LOG.log(Level.FINE,"Rcv packet: userID=" + steamIDSender.getAccountID() + ", " + packetSize + " bytes, chan=" + channel);
            ByteBuffer buf = ByteBuffer.allocate(packetSize);
            for (int i = 0; i < packetSize; i++) {
              buf.put(packetReadBuffer.get(i));
            }
            buf.flip();
            return buf;
          }
        } catch (SteamException ex) {
          if (!isConnected()) {
            return null;
          }
          LOG.log(Level.SEVERE, "Error Receiving Steam Packet", ex);
        }
      }
    }
    return null;
  }

  private boolean firstWrite = true;

  @Override
  public void write(ByteBuffer bb) {
    LOG.log(Level.FINE, "Steam Connector Write user={0}, {1} bytes, chan={2}",
        new Object[]{"" + remoteID.getAccountID(), bb.limit(), channel});
    if (remoteID != null) {
      try {
        ByteBuffer direct = ByteBuffer.allocateDirect(bb.capacity());
        direct.put(bb);
        direct.limit(bb.limit());
        direct.flip();
        networking.sendP2PPacket(remoteID, direct,
            (reliable || firstWrite) ? SteamNetworking.P2PSend.Reliable
                         : SteamNetworking.P2PSend.UnreliableNoDelay
                , channel);
      firstWrite = false;
      } catch (SteamException ex) {
        LOG.log(Level.SEVERE, "Error Sending Steam Packet", ex);
      }

    }
  }
  
}
