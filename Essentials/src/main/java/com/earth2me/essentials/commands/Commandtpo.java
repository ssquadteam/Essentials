package com.earth2me.essentials.commands;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.redis.CrossServerTeleportManager;
import com.earth2me.essentials.redis.CrossServerTeleportManager.RemotePlayer;
import net.ess3.api.TranslatableException;
import org.bukkit.Server;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Commandtpo extends EssentialsCommand {
    public Commandtpo() {
        super("tpo");
    }

    @Override
    public void run(final Server server, final User user, final String commandLabel, final String[] args) throws Exception {
        switch (args.length) {
            case 0:
                throw new NotEnoughArgumentsException();

            case 1:
                final User player = getLocalPlayer(server, user, args, 0);
                if (player == null) {
                    final CrossServerTeleportManager crossServerTeleportManager = getCrossServerTeleportManager();
                    final RemotePlayer remotePlayer = crossServerTeleportManager == null ? null : crossServerTeleportManager.findPlayer(args[0], user.canInteractVanished());
                    if (remotePlayer != null && crossServerTeleportManager.teleportSelfToRemotePlayer(user, remotePlayer)) {
                        return;
                    }
                    throw new PlayerNotFoundException();
                }
                if (user.getWorld() != player.getWorld() && ess.getSettings().isWorldTeleportPermissions() && !user.isAuthorized("essentials.worlds." + player.getWorld().getName())) {
                    throw new TranslatableException("noPerm", "essentials.worlds." + player.getWorld().getName());
                }
                final CompletableFuture<Boolean> selfFuture = getNewExceptionFuture(user.getSource(), commandLabel);
                user.getAsyncTeleport().nowUnsafe(player.getBase().getLocation(), TeleportCause.COMMAND, selfFuture);
                selfFuture.thenAccept(success -> {
                    if (success) {
                        user.sendTl("teleporting", player.getWorld().getName(), player.getLocation().getBlockX(), player.getLocation().getBlockY(), player.getLocation().getBlockZ());
                    }
                });
                break;

            default:
                if (!user.isAuthorized("essentials.tp.others")) {
                    throw new TranslatableException("noPerm", "essentials.tp.others");
                }
                final User target = getLocalPlayer(server, user, args, 0);
                final User toPlayer = getLocalPlayer(server, user, args, 1);
                if (target == null || toPlayer == null) {
                    handleCrossServerTeleport(user, target, toPlayer, args);
                    return;
                }

                if (target.getWorld() != toPlayer.getWorld() && ess.getSettings().isWorldTeleportPermissions() && !user.isAuthorized("essentials.worlds." + toPlayer.getWorld().getName())) {
                    throw new TranslatableException("noPerm", "essentials.worlds." + toPlayer.getWorld().getName());
                }

                final CompletableFuture<Boolean> future = getNewExceptionFuture(user.getSource(), commandLabel);
                target.getAsyncTeleport().nowUnsafe(toPlayer.getBase().getLocation(), TeleportCause.COMMAND, future);
                future.thenAccept(success -> {
                    if (success) {
                        target.sendTl("teleportAtoB", user.getDisplayName(), toPlayer.getDisplayName());
                    }
                });
                break;
        }
    }

    @Override
    protected List<String> getTabCompleteOptions(final Server server, final User user, final String commandLabel, final String[] args) {
        // Don't handle coords
        if (args.length == 1 || (args.length == 2 && user.isAuthorized("essentials.tp.others"))) {
            return getCrossRegionPlayers(user);
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    protected List<String> getTabCompleteOptions(final Server server, final CommandSource sender, final String commandLabel, final String[] args) {
        if (args.length == 1 || args.length == 2) {
            final LinkedHashSet<String> names = new LinkedHashSet<>(getPlayers(sender));
            final CrossServerTeleportManager crossServerTeleportManager = getCrossServerTeleportManager();
            if (crossServerTeleportManager != null) {
                names.addAll(crossServerTeleportManager.getPlayerNames(true));
            }
            return new ArrayList<>(names);
        }
        return Collections.emptyList();
    }

    private void handleCrossServerTeleport(final User user, final User localTarget, final User localDestination, final String[] args) throws Exception {
        final CrossServerTeleportManager crossServerTeleportManager = getCrossServerTeleportManager();
        if (crossServerTeleportManager == null) {
            throw new PlayerNotFoundException();
        }

        final RemotePlayer remoteTarget = localTarget == null ? crossServerTeleportManager.findPlayer(args[0], user.canInteractVanished()) : null;
        final RemotePlayer remoteDestination = localDestination == null ? crossServerTeleportManager.findPlayer(args[1], user.canInteractVanished()) : null;
        if (localTarget == null && remoteTarget == null || localDestination == null && remoteDestination == null) {
            throw new PlayerNotFoundException();
        }

        if (localTarget != null && remoteDestination != null) {
            crossServerTeleportManager.teleportLocalPlayerToRemotePlayer(user, localTarget, remoteDestination);
            return;
        }

        if (remoteTarget != null && localDestination != null) {
            if (ess.getSettings().isWorldTeleportPermissions() && !user.isAuthorized("essentials.worlds." + localDestination.getWorld().getName())) {
                throw new TranslatableException("noPerm", "essentials.worlds." + localDestination.getWorld().getName());
            }
            crossServerTeleportManager.teleportRemotePlayerToLocalPlayer(user, remoteTarget, localDestination);
            return;
        }

        if (remoteTarget != null && remoteDestination != null) {
            crossServerTeleportManager.teleportRemotePlayerToRemotePlayer(user, remoteTarget, remoteDestination);
            return;
        }

        throw new PlayerNotFoundException();
    }

    private User getLocalPlayer(final Server server, final User user, final String[] args, final int pos) throws NotEnoughArgumentsException {
        try {
            return getPlayer(server, user, args, pos);
        } catch (final PlayerNotFoundException ignored) {
            return null;
        }
    }

    private CrossServerTeleportManager getCrossServerTeleportManager() {
        return ((Essentials) ess).getCrossServerTeleportManager();
    }

    private List<String> getCrossRegionPlayers(final User user) {
        final LinkedHashSet<String> names = new LinkedHashSet<>(getPlayers(user));
        final CrossServerTeleportManager crossServerTeleportManager = getCrossServerTeleportManager();
        if (crossServerTeleportManager != null) {
            names.addAll(crossServerTeleportManager.getPlayerNames(user.canInteractVanished()));
        }
        return new ArrayList<>(names);
    }
}
