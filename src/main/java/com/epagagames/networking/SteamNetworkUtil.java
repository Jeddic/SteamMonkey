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

import com.codedisaster.steamworks.SteamID;
import com.jme3.network.Client;
import com.jme3.network.Server;

import java.io.IOException;

public class SteamNetworkUtil {

  public static Server createServer(String gameName,
                                    int version,
                                    String gameDescription,
                                    String serverName,
                                    int maxPlayers,
                                    boolean dedicated,
                                    int port ) throws IOException
  {

    return new SteamServer(gameName, version, gameDescription, serverName, maxPlayers, dedicated, port);
  }

  public static Client createClient(String gameName, int version, SteamID target) throws IOException {
    SteamConnector reliable = new SteamConnector(target, 0, true);
    SteamConnector fast = new SteamConnector(target, 1, false);
//
//    ByteBuffer bufferTest = ByteBuffer.allocateDirect(0);
//    reliable.write(bufferTest);
//    fast.write(bufferTest);
    return new SteamClient(gameName, version, reliable, fast, new SteamConnectorFactory(target));
  }
}
