package me.lucanius.twilight.service.game;

import lombok.Getter;
import lombok.Setter;
import me.lucanius.twilight.Twilight;
import me.lucanius.twilight.service.arena.Arena;
import me.lucanius.twilight.service.game.context.GameContext;
import me.lucanius.twilight.service.game.context.GameState;
import me.lucanius.twilight.service.game.team.GameTeam;
import me.lucanius.twilight.service.game.team.member.TeamMember;
import me.lucanius.twilight.service.loadout.Loadout;
import me.lucanius.twilight.service.queue.abstr.AbstractQueue;
import me.lucanius.twilight.tools.CC;
import me.lucanius.twilight.tools.Scheduler;
import me.lucanius.twilight.tools.Tools;
import me.lucanius.twilight.tools.date.DateTools;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author Lucanius
 * @since May 25, 2022
 */
@Getter @Setter
public class Game {

    private final static Twilight plugin = Twilight.getInstance();

    private final Set<Location> blocks;
    private final Set<UUID> spectators;
    private final UUID uniqueId;
    private final GameContext context;
    private final Loadout loadout;
    private final Arena arena;
    private final AbstractQueue<?> queue;
    private final List<GameTeam> teams;

    private GameState state;
    private long timeStamp;
    private Arena arenaCopy;

    public Game(GameContext context, Loadout loadout, Arena arena, AbstractQueue<?> queue, List<GameTeam> teams) {
        this.blocks = new HashSet<>();
        this.spectators = new HashSet<>();
        this.uniqueId = UUID.randomUUID();
        this.context = context;
        this.loadout = loadout;
        this.arena = arena;
        this.queue = queue;
        this.teams = teams;

        this.state = GameState.STARTING;
        this.timeStamp = System.currentTimeMillis();
        this.arenaCopy = null;
    }

    public void addSpectator(Player player, boolean fromGame) {
        UUID uniqueId = player.getUniqueId();
        spectators.add(uniqueId);

        if (!fromGame) {
            Tools.clearPlayer(player);

            if (!player.hasPermission("twilight.staff")) {
                sendMessage(CC.GAME_PREFIX + CC.SECOND + player.getName() + " is now spectating the game.");
            }

            player.teleport(arena.getMiddle().getBukkitLocation());
            player.setAllowFlight(true);
            player.setFlying(true);

            plugin.getLobby().getSpectatorItems().forEach(item -> player.getInventory().setItem(item.getSlot(), item.getItem()));
            plugin.getOnline().forEach(online -> {
                online.hidePlayer(player);
                player.hidePlayer(online);
            });

            getAlive().forEach(player::showPlayer);
            return;
        }

        MinecraftServer nmsServer = ((CraftServer) plugin.getServer()).getServer();
        WorldServer nmsWorld = ((CraftWorld) player.getWorld()).getHandle();

        EntityPlayer deadNmsPlayer = Tools.getEntityPlayer(player);
        EntityPlayer fakePlayer = new EntityPlayer(nmsServer, nmsWorld, deadNmsPlayer.getProfile(), new PlayerInteractManager(nmsWorld));

        Location location = player.getLocation();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        fakePlayer.setLocation(x, y, z, location.getYaw(), location.getPitch());

        PacketPlayOutSpawnEntityWeather lightning = new PacketPlayOutSpawnEntityWeather(new EntityLightning(deadNmsPlayer.world, x, y, z));
        PacketPlayOutNamedSoundEffect lightningSound = new PacketPlayOutNamedSoundEffect("ambient.weather.thunder", x, y, z, 10.0f, 1.0f);
        PacketPlayOutPlayerInfo remove = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, deadNmsPlayer);
        PacketPlayOutPlayerInfo add = new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, fakePlayer);
        PacketPlayOutNamedEntitySpawn spawn = new PacketPlayOutNamedEntitySpawn(fakePlayer);
        PacketPlayOutEntityStatus status = new PacketPlayOutEntityStatus(fakePlayer, (byte) 9);

        getEveryone().stream().filter(other -> player != other).forEach(other -> {
            PlayerConnection otherConnection = Tools.getEntityPlayer(other).playerConnection;

            otherConnection.sendPacket(lightning);
            otherConnection.sendPacket(lightningSound);
            otherConnection.sendPacket(remove);
            otherConnection.sendPacket(add);
            otherConnection.sendPacket(spawn);
            otherConnection.sendPacket(status);
        });

        PlayerConnection connection = deadNmsPlayer.playerConnection;
        connection.sendPacket(lightning);
        connection.sendPacket(lightningSound);

        Scheduler.run(() -> {
            player.setWalkSpeed(0.0f);
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, -5));
        });

        Scheduler.runLater(() -> {
            getAlive().forEach(member -> member.hidePlayer(player));
            player.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(player::removePotionEffect);
            player.setFlySpeed(0.4f);
            player.setWalkSpeed(0.2f);
            player.setAllowFlight(true);
        }, 20L);
    }

    public void forEachSpectator(Consumer<? super UUID> action) {
        if (!spectators.isEmpty()) {
            spectators.forEach(action);
        }
    }

    public Collection<Player> getEveryone() {
        return teams.stream().flatMap(team -> team.getMembers().stream()).map(TeamMember::getPlayer).collect(Collectors.toList());
    }

    public Collection<Player> getAlive() {
        return teams.stream().flatMap(team -> team.getMembers().stream()).filter(TeamMember::isAlive).map(TeamMember::getPlayer).collect(Collectors.toList());
    }

    public Collection<TeamMember> getMembers() {
        return teams.stream().flatMap(team -> team.getMembers().stream()).collect(Collectors.toList());
    }

    public GameTeam getTeam(UUID uniqueId) {
        return teams.stream().filter(team -> team.getSpecific(uniqueId) != null).findFirst().orElse(null);
    }

    public GameTeam getOpposingTeam(UUID uniqueId) {
        return teams.stream().filter(team -> team.getSpecific(uniqueId) == null).findFirst().orElse(null);
    }

    public GameTeam getOpposingTeam(GameTeam team) {
        return teams.stream().filter(t -> t.getColor() != team.getColor()).findFirst().orElse(null);
    }

    public void sendMessage(String message) {
        getEveryone().forEach(player -> player.sendMessage(CC.translate(message)));
    }

    public void sendSound(Sound sound) {
        getEveryone().forEach(player -> player.playSound(player.getLocation(), sound, 1, 1));
    }

    public void clearBlocks() {
        blocks.forEach(location -> location.getBlock().setType(Material.AIR));
        blocks.clear();
    }

    public String getTime() {
        return DateTools.formatIntToMMSS((int) ((System.currentTimeMillis() - timeStamp) / 1000L));
    }
}
