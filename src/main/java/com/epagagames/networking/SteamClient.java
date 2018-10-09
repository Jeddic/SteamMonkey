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

import com.jme3.network.*;
import com.jme3.network.base.*;
import com.jme3.network.kernel.Connector;
import com.jme3.network.message.ChannelInfoMessage;
import com.jme3.network.message.ClientRegistrationMessage;
import com.jme3.network.message.DisconnectMessage;
import com.jme3.network.service.ClientServiceManager;
import com.jme3.network.service.serializer.ClientSerializerRegistrationsService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Steam Client
 * @author Greg Hoffman
 */
public class SteamClient implements Client {
  static final Logger log = Logger.getLogger(DefaultClient.class.getName());
  private static final int CH_RELIABLE = 0;
  private static final int CH_UNRELIABLE = 1;
  private static final int CH_FIRST = 4;
  private final ThreadLocal<ByteBuffer> dataBuffer;
  private int id;
  private boolean isRunning;
  private final CountDownLatch connecting;
  private String gameName;
  private int version;
  private final MessageListenerRegistry<Client> messageListeners;
  private final List<ClientStateListener> stateListeners;
  private final List<ErrorListener<? super Client>> errorListeners;
  private final Redispatch dispatcher;
  private final List<ConnectorAdapter> channels;
  private ConnectorFactory connectorFactory;
  private ClientServiceManager services;

  public SteamClient(String gameName, int version) {
    this.dataBuffer = new ThreadLocal();
    this.id = -1;
    this.isRunning = false;
    this.connecting = new CountDownLatch(1);
    this.messageListeners = new MessageListenerRegistry();
    this.stateListeners = new CopyOnWriteArrayList();
    this.errorListeners = new CopyOnWriteArrayList();
    this.dispatcher = new Redispatch();
    this.channels = new ArrayList();
    this.gameName = gameName;
    this.version = version;
    this.services = new ClientServiceManager(this);
    this.addStandardServices();
  }

  public SteamClient(String gameName, int version, Connector reliable, Connector fast, ConnectorFactory connectorFactory) {
    this(gameName, version);
    this.setPrimaryConnectors(reliable, fast, connectorFactory);
  }

  protected void addStandardServices() {
    log.fine("Adding standard services...");
    this.services.addService(new ClientSerializerRegistrationsService());
  }

  protected void setPrimaryConnectors(Connector reliable, Connector fast, ConnectorFactory connectorFactory) {
    if (reliable == null) {
      throw new IllegalArgumentException("The reliable connector cannot be null.");
    } else if (this.isRunning) {
      throw new IllegalStateException("Client is already started.");
    } else if (!this.channels.isEmpty()) {
      throw new IllegalStateException("Channels already exist.");
    } else {
      this.connectorFactory = connectorFactory;
      this.channels.add(new ConnectorAdapter(reliable, this.dispatcher, this.dispatcher, true));
      if (fast != null) {
        this.channels.add(new ConnectorAdapter(fast, this.dispatcher, this.dispatcher, false));
      } else {
        this.channels.add(null);
      }

    }
  }

  protected void checkRunning() {
    if (!this.isRunning) {
      throw new IllegalStateException("Client is not started.");
    }
  }

  public void start() {
    if (this.isRunning) {
      throw new IllegalStateException("Client is already started.");
    } else {
      Iterator var1 = this.channels.iterator();

      while(var1.hasNext()) {
        ConnectorAdapter ca = (ConnectorAdapter)var1.next();
        if (ca != null) {
          ca.start();
        }
      }

      long tempId = System.currentTimeMillis() + System.nanoTime();
      this.isRunning = true;
      ClientRegistrationMessage reg = new ClientRegistrationMessage();
      reg.setId(tempId);
      reg.setGameName(this.getGameName());
      reg.setVersion(this.getVersion());
      reg.setReliable(true);
      this.send(0, reg, false);
      reg = new ClientRegistrationMessage();
      reg.setId(tempId);
      reg.setReliable(true);

      for(int ch = 1; ch < this.channels.size(); ++ch) {
        if (this.channels.get(ch) != null) {
          this.send(ch, reg, false);
        }
      }

    }
  }

  public boolean isStarted() {
    return this.isRunning;
  }

  protected void waitForConnected() {
    if (!this.isConnected()) {
      try {
        this.connecting.await();
      } catch (InterruptedException var2) {
        throw new RuntimeException("Interrupted waiting for connect", var2);
      }
    }
  }

  public boolean isConnected() {
    return this.id != -1 && this.isRunning;
  }

  public int getId() {
    return this.id;
  }

  public String getGameName() {
    return this.gameName;
  }

  public int getVersion() {
    return this.version;
  }

  public ClientServiceManager getServices() {
    return this.services;
  }

  public void send(Message message) {
    if (log.isLoggable(Level.FINER)) {
      log.log(Level.FINER, "send({0})", message);
    }

    if (!message.isReliable() && this.channels.get(1) != null) {
      this.send(1, message, true);
    } else {
      this.send(0, message, true);
    }

  }

  public void send(int channel, Message message) {
    if (log.isLoggable(Level.FINER)) {
      log.log(Level.FINER, "send({0}, {1})", new Object[]{channel, message});
    }

    if (channel >= 0) {
      this.waitForConnected();
    }

    if (channel >= -2 && channel + 2 < this.channels.size()) {
      this.send(channel + 2, message, true);
    } else {
      throw new IllegalArgumentException("Channel is undefined:" + channel);
    }
  }

  protected void send(int channel, Message message, boolean waitForConnected) {
    this.checkRunning();
    if (waitForConnected) {
      this.waitForConnected();
    }

    ByteBuffer buffer = (ByteBuffer)this.dataBuffer.get();
    if (buffer == null) {
      buffer = ByteBuffer.allocate(65538);
      this.dataBuffer.set(buffer);
    }

    buffer.clear();
    buffer = MessageProtocol.messageToBuffer(message, buffer);
    byte[] temp = new byte[buffer.remaining()];
    System.arraycopy(buffer.array(), buffer.position(), temp, 0, buffer.remaining());
    buffer = ByteBuffer.wrap(temp);
    ((ConnectorAdapter)this.channels.get(channel)).write(buffer);
  }

  public void close() {
    this.checkRunning();

    // send steam disconnect message
    DisconnectMessage dm = new DisconnectMessage();
    dm.setReason("Client Disconnect");
    dm.setType("Normal");
    send(0, dm, false);

    this.closeConnections((ClientStateListener.DisconnectInfo)null);
  }

  protected void closeConnections(ClientStateListener.DisconnectInfo info) {
    synchronized(this) {
      if (!this.isRunning) {
        return;
      }

      if (this.services.isStarted()) {
        this.services.stop();
      }

      Iterator var3 = this.channels.iterator();

      while(true) {
        if (!var3.hasNext()) {
          this.connecting.countDown();
          this.isRunning = false;
          this.services.terminate();
          break;
        }

        ConnectorAdapter ca = (ConnectorAdapter)var3.next();
        if (ca != null) {
          ca.close();
        }
      }
    }

    this.fireDisconnected(info);
  }

  public void addClientStateListener(ClientStateListener listener) {
    this.stateListeners.add(listener);
  }

  public void removeClientStateListener(ClientStateListener listener) {
    this.stateListeners.remove(listener);
  }

  public void addMessageListener(MessageListener<? super Client> listener) {
    this.messageListeners.addMessageListener(listener);
  }

  public void addMessageListener(MessageListener<? super Client> listener, Class... classes) {
    this.messageListeners.addMessageListener(listener, classes);
  }

  public void removeMessageListener(MessageListener<? super Client> listener) {
    this.messageListeners.removeMessageListener(listener);
  }

  public void removeMessageListener(MessageListener<? super Client> listener, Class... classes) {
    this.messageListeners.removeMessageListener(listener, classes);
  }

  public void addErrorListener(ErrorListener<? super Client> listener) {
    this.errorListeners.add(listener);
  }

  public void removeErrorListener(ErrorListener<? super Client> listener) {
    this.errorListeners.remove(listener);
  }

  protected void fireConnected() {
    Iterator var1 = this.stateListeners.iterator();

    while(var1.hasNext()) {
      ClientStateListener l = (ClientStateListener)var1.next();
      l.clientConnected(this);
    }

  }

  protected void startServices() {
    log.fine("Starting client services.");
    this.services.start();
  }

  protected void fireDisconnected(ClientStateListener.DisconnectInfo info) {
    Iterator var2 = this.stateListeners.iterator();

    while(var2.hasNext()) {
      ClientStateListener l = (ClientStateListener)var2.next();
      l.clientDisconnected(this, info);
    }

  }

  protected void handleError(Throwable t) {
    if (this.errorListeners.isEmpty()) {
      log.log(Level.SEVERE, "Termining connection due to unhandled error", t);
      ClientStateListener.DisconnectInfo info = new ClientStateListener.DisconnectInfo();
      info.reason = "Connection Error";
      info.error = t;
      this.closeConnections(info);
    } else {
      Iterator var2 = this.errorListeners.iterator();

      while(var2.hasNext()) {
        ErrorListener l = (ErrorListener)var2.next();
        l.handleError(this, t);
      }

    }
  }

  protected void configureChannels(long tempId, int[] ports) {
    try {
      for(int i = 0; i < ports.length; ++i) {
        Connector c = this.connectorFactory.createConnector(i, ports[i]);
        ConnectorAdapter ca = new ConnectorAdapter(c, this.dispatcher, this.dispatcher, true);
        int ch = this.channels.size();
        this.channels.add(ca);
        ca.start();
        ClientRegistrationMessage reg = new ClientRegistrationMessage();
        reg.setId(tempId);
        reg.setReliable(true);
        this.send(ch, reg, false);
      }

    } catch (IOException var9) {
      throw new RuntimeException("Error configuring channels", var9);
    }
  }

  protected void dispatch(Message m) {
    if (log.isLoggable(Level.FINER)) {
      log.log(Level.FINER, "{0} received:{1}", new Object[]{this, m});
    }

    if (m instanceof ClientRegistrationMessage) {
      ClientRegistrationMessage crm = (ClientRegistrationMessage)m;
      if (crm.getId() >= 0L) {
        this.id = (int)crm.getId();
        log.log(Level.FINE, "Connection established, id:{0}.", this.id);
        this.connecting.countDown();
      } else {
        this.startServices();
        this.fireConnected();
      }

    } else if (m instanceof ChannelInfoMessage) {
      this.configureChannels(((ChannelInfoMessage)m).getId(), ((ChannelInfoMessage)m).getPorts());
    } else {
      if (m instanceof DisconnectMessage) {
        String reason = ((DisconnectMessage)m).getReason();
        log.log(Level.SEVERE, "Connection terminated, reason:{0}.", reason);
        ClientStateListener.DisconnectInfo info = new ClientStateListener.DisconnectInfo();
        info.reason = reason;
        this.closeConnections(info);
      }

      synchronized(this) {
        this.messageListeners.messageReceived(this, m);
      }
    }
  }

  protected class Redispatch implements MessageListener<Object>, ErrorListener<Object> {
    protected Redispatch() {
    }

    public void messageReceived(Object source, Message m) {
      SteamClient.this.dispatch(m);
    }

    public void handleError(Object source, Throwable t) {
      SteamClient.this.handleError(t);
    }
  }
}
