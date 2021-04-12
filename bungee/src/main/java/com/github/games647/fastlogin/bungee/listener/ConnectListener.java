/*
 * SPDX-License-Identifier: MIT
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2021 <Your name and contributors>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.fastlogin.bungee.listener;

import com.github.games647.craftapi.UUIDAdapter;
import com.github.games647.fastlogin.bungee.BungeeLoginSession;
import com.github.games647.fastlogin.bungee.FastLoginBungee;
import com.github.games647.fastlogin.bungee.task.AsyncPremiumCheck;
import com.github.games647.fastlogin.bungee.task.ForceLoginTask;
import com.github.games647.fastlogin.core.RateLimiter;
import com.github.games647.fastlogin.core.StoredProfile;
import com.github.games647.fastlogin.core.shared.LoginSession;
import com.google.common.base.Throwables;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.util.UUID;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.connection.InitialHandler;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.connection.LoginResult.Property;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import org.geysermc.floodgate.FloodgateAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enables online mode logins for specified users and sends plugin message to the Bukkit version of this plugin in
 * order to clear that the connection is online mode.
 */
public class ConnectListener implements Listener {

    private static final String UUID_FIELD_NAME = "uniqueId";
    private static final boolean initialHandlerClazzFound;
    private static final MethodHandle uniqueIdSetter;

    static {
        MethodHandle setHandle = null;
        boolean handlerFound = false;
        try {
            Lookup lookup = MethodHandles.lookup();

            Class.forName("net.md_5.bungee.connection.InitialHandler");
            handlerFound = true;

            Field uuidField = InitialHandler.class.getDeclaredField(UUID_FIELD_NAME);
            uuidField.setAccessible(true);
            setHandle = lookup.unreflectSetter(uuidField);
        } catch (ClassNotFoundException classNotFoundException) {
            Logger logger = LoggerFactory.getLogger(ConnectListener.class);
            logger.error(
                    "Cannot find Bungee initial handler; Disabling premium UUID and skin won't work.",
                    classNotFoundException
            );
        } catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }

        initialHandlerClazzFound = handlerFound;
        uniqueIdSetter = setHandle;
    }

    private final FastLoginBungee plugin;
    private final RateLimiter rateLimiter;
    private final Property[] emptyProperties = {};
    private final boolean floodGateAvailable;

    public ConnectListener(FastLoginBungee plugin, RateLimiter rateLimiter, boolean floodgateAvailable) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
        this.floodGateAvailable = floodgateAvailable;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent preLoginEvent) {
        PendingConnection connection = preLoginEvent.getConnection();
        if (preLoginEvent.isCancelled() || isBedrockPlayer(connection.getUniqueId())) {
            return;
        }

        if (!rateLimiter.tryAcquire()) {
            //plugin.getLog().warn("Join limit hit - Ignoring player {}", connection);
            plugin.getLog().warn("Join limit hit - Kicking player {}", connection);
            preLoginEvent.setCancelled(true);
            preLoginEvent.setCancelReason("§cLe serveur rencontre actuellement un nombre de connexions élevé.\n\n§fMerci de revenir dans quelques minutes !");
            return;
        }

        String username = connection.getName();
        plugin.getLog().info("Incoming login request for {} from {}", username, connection.getSocketAddress());

        preLoginEvent.registerIntent(plugin);
        Runnable asyncPremiumCheck = new AsyncPremiumCheck(plugin, preLoginEvent, connection, username);
        plugin.getScheduler().runAsync(asyncPremiumCheck);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent loginEvent) {
        if (loginEvent.isCancelled()) {
            return;
        }

        //use the login event instead of the post login event in order to send the login success packet to the client
        //with the offline uuid this makes it possible to set the skin then
        PendingConnection connection = loginEvent.getConnection();
        if (connection.isOnlineMode()) {
            LoginSession session = plugin.getSession().get(connection);

            UUID verifiedUUID = connection.getUniqueId();
            String verifiedUsername = connection.getName();
            session.setUuid(verifiedUUID);
            session.setVerifiedUsername(verifiedUsername);

            StoredProfile playerProfile = session.getProfile();
            playerProfile.setId(verifiedUUID);

            // bungeecord will do this automatically so override it on disabled option
            if (uniqueIdSetter != null) {
                InitialHandler initialHandler = (InitialHandler) connection;

                if (!plugin.getCore().getConfig().get("premiumUuid", true)) {
                    setOfflineId(initialHandler, verifiedUsername);
                }

                if (!plugin.getCore().getConfig().get("forwardSkin", true)) {
                    // this is null on offline mode
                    LoginResult loginProfile = initialHandler.getLoginProfile();
                    loginProfile.setProperties(emptyProperties);
                }
            }
        }
    }

    private void setOfflineId(InitialHandler connection, String username) {
        try {
            final UUID oldPremiumId = connection.getUniqueId();
            final UUID offlineUUID = UUIDAdapter.generateOfflineId(username);

            // BungeeCord only allows setting the UUID in PreLogin events and before requesting online mode
            // However if online mode is requested, it will override previous values
            // So we have to do it with reflection
            uniqueIdSetter.invokeExact(connection, offlineUUID);

            String format = "Overridden UUID from {} to {} (based of {}) on {}";
            plugin.getLog().info(format, oldPremiumId, offlineUUID, username, connection);
        } catch (Exception ex) {
            plugin.getLog().error("Failed to set offline uuid of {}", username, ex);
        } catch (Throwable throwable) {
            // throw remaining exceptions like outofmemory that we shouldn't handle ourself
            Throwables.throwIfUnchecked(throwable);
        }
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent serverConnectedEvent) {
        ProxiedPlayer player = serverConnectedEvent.getPlayer();
        Server server = serverConnectedEvent.getServer();

        BungeeLoginSession session = plugin.getSession().get(player.getPendingConnection());
        if (session == null) {
            return;
        }

        // delay sending force command, because Paper will process the login event asynchronously
        // In this case it means that the force command (plugin message) is already received and processed while
        // player is still in the login phase and reported to be offline.
        Runnable loginTask = new ForceLoginTask(plugin.getCore(), player, server, session);
        plugin.getScheduler().runAsync(loginTask);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent disconnectEvent) {
        ProxiedPlayer player = disconnectEvent.getPlayer();
        plugin.getSession().remove(player.getPendingConnection());
        plugin.getCore().getPendingConfirms().remove(player.getUniqueId());
    }

    private boolean isBedrockPlayer(UUID correctedUUID) {
        // Floodgate will set a correct UUID at the beginning of the PreLoginEvent
        // and will cancel the online mode login for those players
        // Therefore we just ignore those
        return floodGateAvailable && FloodgateAPI.isBedrockPlayer(correctedUUID);
    }
}
