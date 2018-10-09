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
import com.codedisaster.steamworks.SteamNetworking.P2PSend;
import com.jme3.network.kernel.Endpoint;
import com.jme3.network.kernel.Kernel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SteamEndpoint
 * @author Greg
 */
public class SteamEndpoint implements Endpoint {

  private static final Logger LOG = Logger.getLogger(SteamEndpoint.class.getSimpleName());

	private static final int defaultChannel = 0;
  
  private long id;
  private SteamID remoteUserAddress;
  private SteamKernel kernel;
  private SteamNetworking networking;
  private P2PSend sendType = P2PSend.Reliable;
  
  public SteamEndpoint(long id, SteamID remoteUser, SteamKernel kernel, SteamNetworking networking) {
    this.id = id;
    this.remoteUserAddress = remoteUser;
    this.kernel = kernel;
    this.networking = networking;
    sendType = kernel.isReliable() ? P2PSend.Reliable : P2PSend.UnreliableNoDelay;
  }

  @Override
  public long getId() {
    return id;
  }

  public SteamID getSteamID() { return remoteUserAddress; }

  public int getSteamUserID() {
    return remoteUserAddress.getAccountID();
  }

  @Override
  public String getAddress() {
    return "" + remoteUserAddress.getAccountID();
  }

  @Override
  public Kernel getKernel() {
    return kernel;
  }

  @Override
  public boolean isConnected() {
    SteamNetworking.P2PSessionState state = new SteamNetworking.P2PSessionState();
    networking.getP2PSessionState(remoteUserAddress, state);
    return state.isConnectionActive();
  }

  private int getActualChannel() {
    int channel = kernel.getChannel();
    if (channel == 0) return 2;
    if (channel == 1) return 3;
    return channel;
  }

  private int getActualChannel(int chan) {
    int channel = chan;
    if (channel == 0) return 2;
    if (channel == 1) return 3;
    return channel;
  }

  public void send(ByteBuffer bb, P2PSend type, int channel) {
    LOG.log(Level.FINE, "Steam Endpoint Send Packet userID={0}, type={1}, {2} bytes",
        new Object[]{remoteUserAddress.getAccountID(), type.toString(), bb.capacity()});
    if (remoteUserAddress != null) {
      try {
        networking.sendP2PPacket(remoteUserAddress, bb,
            type, getActualChannel(channel));
      } catch (SteamException ex) {
        LOG.log(Level.SEVERE, "Error Sending Steam Packet", ex);
      }
    }
  }

  @Override
  public void send(ByteBuffer bb) {
    LOG.log(Level.FINE,"Steam Endpoint Send Packet userID={0}, type={1}, {2} bytes",
        new Object[]{remoteUserAddress.getAccountID(), sendType.toString(), bb.capacity()});
    if (remoteUserAddress != null) {
      try {
        networking.sendP2PPacket(remoteUserAddress, bb,
            sendType, getActualChannel());
      } catch (SteamException ex) {
        LOG.log(Level.SEVERE, "Error Sending Steam Packet", ex);
      }
    }
  }

  @Override
  public void close() {
    close(false);
  }

  @Override
  public void close(boolean bln) {
    LOG.log(Level.INFO, "Closing Steam Endpoint user={0}, chan={1}",
        new Object[]{remoteUserAddress.getAccountID(), defaultChannel});
    try {
      kernel.closeEndpoint(this);
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "Error closing Steam Endpoint");
    }
  }
  
  @Override
  public String toString()
  {
    return "SteamEndpoint[" + id + ", " + remoteUserAddress.getAccountID() + "]";
  }
}
