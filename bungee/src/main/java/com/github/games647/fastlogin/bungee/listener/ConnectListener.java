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

/**
 * Enables online mode logins for specified users and sends plugin message to the Bukkit version of this plugin in
 * order to clear that the connection is online mode.
 */
public class ConnectListener implements Listener {

    private final FastLoginBungee plugin;
    private final RateLimiter rateLimiter;

    private final Property[] emptyProperties = {};
    private final boolean floodGateAvailable;


    private static final String UUID_FIELD_NAME = "uniqueId";
    private static final MethodHandle uniqueIdSetter;

    static {
        MethodHandle setHandle = null;
        try {
            Lookup lookup = MethodHandles.lookup();

            Field uuidField = InitialHandler.class.getDeclaredField(UUID_FIELD_NAME);
            uuidField.setAccessible(true);
            setHandle = lookup.unreflectSetter(uuidField);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }

        uniqueIdSetter = setHandle;
    }

    public ConnectListener(FastLoginBungee plugin, RateLimiter rateLimiter, boolean floodgateAvailable) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
        this.floodGateAvailable = floodgateAvailable;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent preLoginEvent) {
        if (preLoginEvent.isCancelled() || isBedrockPlayer(preLoginEvent.getConnection().getUniqueId())) {
            return;
        }

        PendingConnection connection = preLoginEvent.getConnection();
        if (!rateLimiter.tryAcquire()) {
            //plugin.getLog().warn("Join limit hit - Ignoring player {}", connection);
            plugin.getLog().warn("Join limit hit - Kicking player {}", connection);
            preLoginEvent.setCancelled(true);
            preLoginEvent.setCancelReason("§cLe serveur rencontre actuellement un nombre de connexions élevé.\n\n§fMerci de revenir dans quelques minutes !");
            return;
        }

        InitialHandler initialHandler = (InitialHandler) connection;
        String username = initialHandler.getLoginRequest().getData();
        plugin.getLog().info("Incoming login request for {} from {}", username, initialHandler.getAddress());

        preLoginEvent.registerIntent(plugin);
        Runnable asyncPremiumCheck = new AsyncPremiumCheck(plugin, preLoginEvent, connection);
        plugin.getScheduler().runAsync(asyncPremiumCheck);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(LoginEvent loginEvent) {
        if (loginEvent.isCancelled()) {
            return;
        }

        //use the login event instead of the post login event in order to send the login success packet to the client
        //with the offline uuid this makes it possible to set the skin then
        final PendingConnection connection = loginEvent.getConnection();
        final InitialHandler initialHandler = (InitialHandler) connection;

        if (connection.isOnlineMode()) {
            LoginSession session = plugin.getSession().get(connection);
            LoginResult loginProfile = initialHandler.getLoginProfile();

            UUID verifiedUUID = connection.getUniqueId();
            session.setUuid(verifiedUUID);
            session.setVerifiedUsername(loginProfile.getName());

            StoredProfile playerProfile = session.getProfile();
            playerProfile.setId(verifiedUUID);

            //bungeecord will do this automatically so override it on disabled option
            if (!plugin.getCore().getConfig().get("premiumUuid", true)) {
                String username = loginProfile.getName();
                setOfflineId(initialHandler, username);
            }

            if (!plugin.getCore().getConfig().get("forwardSkin", true)) {
                // this is null on offline mode
                loginProfile.setProperties(emptyProperties);
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
