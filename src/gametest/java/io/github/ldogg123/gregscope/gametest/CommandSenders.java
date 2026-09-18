package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.mojang.authlib.GameProfile;

/**
 * Command senders that record what {@code /gregscope} answers (GS-111).
 *
 * <p>
 * A plain {@code FakePlayer} cannot be used directly: {@code EntityPlayerMP.addChatMessage} sends a packet through
 * {@code playerNetServerHandler}, which a fake player does not have, and {@code FakePlayer} answers every permission
 * question with false. {@link Player} overrides exactly those two, so the command sees a real
 * {@code EntityPlayer} with a real UUID whose permission level the test chooses - which is what the design-v0.2 §5
 * rows are about.
 *
 * <p>
 * {@link Console} is any non-player sender (the server console, RCON, a command block): the command turns it into
 * {@code Viewer.CONSOLE}, which §5 counts as op. It is a stub rather than {@code MinecraftServer} itself, because
 * {@code MinecraftServer.addChatMessage} logs every line, and one of the assertions here is that a command call logs
 * nothing.
 *
 * <p>
 * {@link Real} (GS-112) is the third kind: an {@code EntityPlayerMP} that is <b>not</b> a {@code FakePlayer}, with the
 * same recorded chat and chosen permission level. GregScope treats a FakePlayer as "no attributable player" on
 * purpose (design-v0.2 §3.4 and §9.1), so a test that needs a placement to produce an <b>owner</b> cannot use
 * {@link Player}. It is built exactly as Forge's {@code FakePlayer} is, minus the overrides that make one fake.
 */
final class CommandSenders {

    private CommandSenders() {}

    /** A player sender that is not an operator (the §5 "stranger" and "any player" rows). */
    static Player player(GameTestHelper helper, String name) {
        return player(helper, name, -1);
    }

    /**
     * @param permissionLevel the highest level {@code canCommandSenderUseCommand} answers true for; -1 is "never",
     *                        which is what vanilla answers for a player who is not on the ops list
     */
    static Player player(GameTestHelper helper, String name, int permissionLevel) {
        return player(
            helper,
            name,
            UUID.nameUUIDFromBytes(("gregscope-cmd:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            permissionLevel);
    }

    /**
     * A sender with a chosen UUID, so the same player can be put into a real GTNHLib team and own a sensor.
     * {@code EntityPlayer.getUniqueID()} returns the profile's id when it is not null, so this is that UUID.
     */
    static Player player(GameTestHelper helper, String name, UUID uuid, int permissionLevel) {
        return new Player(helper.getWorld(), new GameProfile(uuid, name), permissionLevel);
    }

    static Console console() {
        return new Console();
    }

    /**
     * A real (non-FakePlayer) player with a chosen UUID and permission level, for the rows that are about an owner.
     *
     * @param permissionLevel the highest level {@code canCommandSenderUseCommand} answers true for; -1 is "never"
     */
    static Real realPlayer(GameTestHelper helper, String name, UUID uuid, int permissionLevel) {
        return new Real(helper.getWorld(), new GameProfile(uuid, name), permissionLevel);
    }

    /** A real player with a UUID derived from its name. */
    static Real realPlayer(GameTestHelper helper, String name, int permissionLevel) {
        return realPlayer(
            helper,
            name,
            UUID.nameUUIDFromBytes(("gregscope-real:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            permissionLevel);
    }

    /** What a sender was told, by {@code ChatComponentTranslation} key. */
    static final class ChatLog {

        private final List<IChatComponent> messages = new ArrayList<>();

        void add(IChatComponent message) {
            messages.add(message);
        }

        /** One key per line, in order; a line that is not a translation is recorded as {@code "?"}. */
        List<String> keys() {
            List<String> out = new ArrayList<>();
            for (IChatComponent message : messages) {
                out.add(
                    message instanceof ChatComponentTranslation ? ((ChatComponentTranslation) message).getKey() : "?");
            }
            return out;
        }

        boolean has(String key) {
            return keys().contains(key);
        }

        int count(String key) {
            int n = 0;
            for (String seen : keys()) {
                if (seen.equals(key)) {
                    n++;
                }
            }
            return n;
        }

        /** The arguments of the first line with this key, or null if there is none. */
        Object[] args(String key) {
            for (IChatComponent message : messages) {
                if (message instanceof ChatComponentTranslation && ((ChatComponentTranslation) message).getKey()
                    .equals(key)) {
                    return ((ChatComponentTranslation) message).getFormatArgs();
                }
            }
            return null;
        }

        /** Every line's arguments joined, for "does any row mention this sensor" assertions. */
        String flatten() {
            StringBuilder out = new StringBuilder();
            for (IChatComponent message : messages) {
                if (message instanceof ChatComponentTranslation) {
                    ChatComponentTranslation line = (ChatComponentTranslation) message;
                    out.append(line.getKey());
                    for (Object arg : line.getFormatArgs()) {
                        out.append(' ')
                            .append(arg);
                    }
                } else {
                    out.append(message.getUnformattedTextForChat());
                }
                out.append('\n');
            }
            return out.toString();
        }

        int size() {
            return messages.size();
        }

        void clear() {
            messages.clear();
        }

        @Override
        public String toString() {
            return flatten();
        }
    }

    /** A {@code FakePlayer} that records chat instead of sending a packet, with a chosen permission level. */
    static final class Player extends FakePlayer {

        final ChatLog chat = new ChatLog();
        private final int permissionLevel;

        private Player(net.minecraft.world.WorldServer world, GameProfile profile, int permissionLevel) {
            super(world, profile);
            this.permissionLevel = permissionLevel;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            chat.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return permissionLevel >= level;
        }
    }

    /**
     * An {@code EntityPlayerMP} that is not a {@code FakePlayer}: it records chat instead of sending a packet (it has
     * no {@code playerNetServerHandler}) and answers permission questions at a chosen level. Built the same way
     * {@code FakePlayer} builds itself (Forge {@code FakePlayer.java:21-23}).
     */
    static final class Real extends net.minecraft.entity.player.EntityPlayerMP {

        final ChatLog chat = new ChatLog();
        private final int permissionLevel;

        private Real(net.minecraft.world.WorldServer world, GameProfile profile, int permissionLevel) {
            super(
                cpw.mods.fml.common.FMLCommonHandler.instance()
                    .getMinecraftServerInstance(),
                world,
                profile,
                new net.minecraft.server.management.ItemInWorldManager(world));
            this.permissionLevel = permissionLevel;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            chat.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return permissionLevel >= level;
        }
    }

    /** Any non-player sender; the command turns it into {@code Viewer.CONSOLE}. */
    static final class Console implements ICommandSender {

        final ChatLog chat = new ChatLog();

        @Override
        public String getCommandSenderName() {
            return "gregscope-test-console";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return new ChatComponentText(getCommandSenderName());
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            chat.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        @Override
        public World getEntityWorld() {
            return net.minecraft.server.MinecraftServer.getServer()
                .getEntityWorld();
        }
    }
}
