/*
 * Forbric Bukkit bridge — plugin.yml commands, in the command map and in the game's dispatcher.
 */
package org.cn.xiaofan.bukkit;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.commands.CommandSourceStack;

/**
 * Turns a plugin's {@code plugin.yml} {@code commands:} section into commands this server can run.
 *
 * <h2>Why this is a separate path from {@code Commands.register}</h2>
 *
 * <p>Bukkit has two ways to declare a command and they are not the same thing:
 *
 * <ul>
 *   <li>A modern Paper plugin calls {@code LifecycleEvents.COMMANDS} and builds a Brigadier tree. That
 *       path goes through {@link ForbricCommands}, and the plugin gets the API's argument types and
 *       resolvers.
 *   <li>A traditional plugin writes the command's name, aliases and permission in {@code plugin.yml}
 *       and then calls {@code getCommand("name").setExecutor(...)} in {@code onEnable}. There is no
 *       tree: the plugin receives {@code (sender, label, String[] args)} and parses the words itself.
 * </ul>
 *
 * <p>Only the first was wired up. The second declared nothing the game could see, and the failure was
 * quiet in the worst way: {@code getCommand(name)} answered <b>null</b> on a server whose plugin had
 * just declared that command, so a plugin calling {@code setExecutor} on the result threw
 * {@code NullPointerException} inside its own {@code onEnable} — a stack trace naming the plugin, not
 * the bridge. Every plugin written before Paper's Brigadier API was affected, which is most of them.
 *
 * <h2>Two jobs, and why both are needed</h2>
 *
 * <p>A declared command has to exist in two places at once:
 *
 * <ol>
 *   <li><b>The command map</b>, so {@code plugin.getCommand(name)} answers with a command the plugin
 *       can attach its executor to. This is Bukkit's own registry and the only thing a traditional
 *       plugin ever touches.
 *   <li><b>The game's dispatcher</b>, so typing the command reaches that executor at all.
 * </ol>
 *
 * <p>Doing the first alone leaves a command that exists and cannot be run; the second alone leaves a
 * node with no executor and a {@code getCommand} that still answers null. Both are done here, and the
 * node looks the command up per invocation rather than capturing an executor — so
 * {@code setExecutor} works whenever the plugin calls it, which is the order every plugin uses.
 *
 * <h2>The {@link PluginCommand} is built reflectively</h2>
 *
 * <p>Its constructor is package-private in {@code org.bukkit.command}, so it cannot be called from
 * here, and a subclass is not an option either: the class is {@code final}. Reflection is what
 * remains, and it is not a hazard here — the class, the constructor and this bridge all come from the
 * same paper-api jar on the same loader, so there is no lookup that can resolve to a different class
 * than {@link SimpleCommandMap#register} expects.
 */
final class ForbricDeclaredCommands {

    /** The greedy argument's name. */
    private static final String ARGUMENTS = "args";

    /** {@code PluginCommand(String, Plugin)}, resolved once — a miss here is a broken paper-api. */
    private static Constructor<PluginCommand> constructor;

    private ForbricDeclaredCommands() {
    }

    /**
     * Registers every command this plugin declared.
     *
     * <p>Called after the lifecycle handlers, so a plugin that declares a command in both places has
     * already built its Brigadier tree and this cannot shadow it: an existing label is left alone and
     * said so in the log. On a real server the {@code plugin.yml} entries go in first and a Brigadier
     * registration merges into the same label, so the tree is the richer definition either way.
     */
    static void install(CommandDispatcher<CommandSourceStack> dispatcher, PluginDescriptionFile description,
                    Plugin plugin, ServerImpl server) {
        Map<String, Map<String, Object>> declared = description.getCommands();
        if (declared == null || declared.isEmpty()) return;

        SimpleCommandMap commandMap = commandMapOf(server);
        if (commandMap == null) {
            ForbricLog.warn("[Forbric/Bukkit] %s declares %d command(s) in plugin.yml but no command map is "
                    + "available, so getCommand() keeps answering null and the plugin's onEnable may fail on it",
                    description.getName(), declared.size());
            return;
        }

        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            String label = entry.getKey();
            Map<String, Object> properties = entry.getValue() == null ? Map.of() : entry.getValue();
            List<String> aliases = aliasesOf(properties);

            PluginCommand command = newPluginCommand(label, plugin, properties);
            if (command == null) continue;

            // Bukkit's own registry, under the fallback prefix the plugin's name gives it. register() is what
            // makes getCommand() and the alias lookup work.
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);

            // The game's dispatcher, once per label. A Brigadier tree that already owns one of these names keeps
            // it: the tree is a richer definition of the same command and the plugin asked for both.
            for (String name : labels(label, aliases)) {
                if (dispatcher.getRoot().getChild(name) != null) {
                    ForbricLog.info("[Forbric/Bukkit] %s declares /%s in plugin.yml, but a Brigadier tree "
                            + "already defines it — the tree stays", description.getName(), name);
                    continue;
                }
                dispatcher.getRoot().addChild(node(name, commandMap, server));
            }
        }
    }

    /** The server's command map, or null when the bridge has not built one. */
    private static SimpleCommandMap commandMapOf(ServerImpl server) {
        try {
            return server.commandMap();
        } catch (RuntimeException | Error unavailable) {
            return null;
        }
    }

    /**
     * One {@link PluginCommand}, built through the package-private constructor.
     *
     * <p>The permission and its message come off the {@code plugin.yml} entry because
     * {@code PluginCommand.execute} is where Bukkit checks the permission before running an executor.
     * Leaving them off would let a sender who was declared out of the command run it.
     */
    private static PluginCommand newPluginCommand(String label, Plugin plugin, Map<String, Object> properties) {
        try {
            if (constructor == null) {
                constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
                constructor.setAccessible(true);
            }
            PluginCommand command = constructor.newInstance(label, plugin);
            String usage = string(properties.get("usage"));
            if (usage != null) command.setUsage(usage);
            String description = string(properties.get("description"));
            if (description != null) command.setDescription(description);
            String permission = string(properties.get("permission"));
            if (permission != null) {
                command.setPermission(permission);
                String message = string(properties.get("permission-message"));
                if (message != null) command.setPermissionMessage(message);
            }
            return command;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            ForbricLog.warn("[Forbric/Bukkit] could not build a PluginCommand for /%s declared by %s (%s) — "
                    + "getCommand(\"%s\") answers null for it, and a plugin that calls setExecutor on that "
                    + "result fails in its own onEnable", label, plugin.getName(),
                    unavailable.getClass().getName(), label);
            return null;
        }
    }

    /** A {@code plugin.yml} string value, or null when absent or blank. */
    private static String string(Object raw) {
        if (raw == null) return null;
        String value = raw.toString();
        return value.isBlank() ? null : value;
    }

    /** The labels one declared command answers to: its own name first, then its aliases. */
    private static Collection<String> labels(String label, List<String> aliases) {
        List<String> all = new ArrayList<>();
        all.add(label);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank() && !all.contains(alias)) all.add(alias);
        }
        return all;
    }

    /** The {@code aliases} list from a {@code plugin.yml} entry, which is a YAML list or a single string. */
    private static List<String> aliasesOf(Map<String, Object> properties) {
        Object raw = properties.get("aliases");
        if (raw instanceof String single) {
            return single.isBlank() ? List.of() : List.of(single.trim());
        }
        if (raw instanceof Collection<?> many) {
            List<String> aliases = new ArrayList<>();
            for (Object one : many) {
                if (one != null) aliases.add(one.toString());
            }
            return aliases;
        }
        return List.of();
    }

    /**
     * One label's node: the bare label, plus a greedy child for everything typed after it.
     *
     * <p>The child is {@code greedyString} because a {@code CommandExecutor} parses its own words and a
     * greedy argument is the only one that imposes no grammar — it takes the rest of the line, spaces
     * included. Nothing about what the command accepts can be known here; the plugin decides.
     */
    private static LiteralCommandNode<CommandSourceStack> node(String name, SimpleCommandMap commandMap,
                    ServerImpl server) {
        LiteralArgumentBuilder<CommandSourceStack> builder = LiteralArgumentBuilder
                .<CommandSourceStack>literal(name)
                .executes(context -> run(context, commandMap, name, new String[0], server))
                .then(RequiredArgumentBuilder
                        .<CommandSourceStack, String>argument(ARGUMENTS, StringArgumentType.greedyString())
                        .executes(context -> run(context, commandMap, name,
                                words(StringArgumentType.getString(context, ARGUMENTS)), server)));
        return builder.build();
    }

    /**
     * Runs the command through the map, not through a captured executor.
     *
     * <p>Looking it up per invocation is what makes {@code setExecutor} work whenever the plugin calls
     * it, including after its {@code onEnable} has returned. A node holding the executor it saw at
     * registration time would keep running the first one, and for a plugin that sets its executors
     * late it would be running null.
     *
     * <p>The map is asked for the typed name, so an alias runs under the alias: Bukkit's own dispatch
     * passes the label as typed, and a plugin that branches on it must see what the player wrote.
     */
    private static int run(CommandContext<CommandSourceStack> context, SimpleCommandMap commandMap,
                    String name, String[] args, ServerImpl server) {
        org.bukkit.command.Command command = commandMap.getCommand(name);
        if (command == null) {
            // Registered at enable and gone now: the plugin was disabled. Saying so beats a silent no-op that
            // looks like a permission refusal.
            ForbricLog.warn("[Forbric/Bukkit] /%s was declared by a plugin that is no longer loaded", name);
            return 0;
        }
        CommandSender sender = ForbricCommandSender.of(context.getSource(), server);
        boolean handled = command.execute(sender, name, args);
        return handled ? 1 : 0;
    }

    /**
     * The words after the label, with quoting resolved.
     *
     * <p>{@code greedyString} keeps the quotes as typed, so the run is handed to a fresh reader and read
     * word by word — the same rules the game's parser applied to the line before this node saw it.
     * {@code /kit give "my kit"} is two words, and splitting the raw tail on spaces would make it three.
     */
    private static String[] words(String rest) {
        if (rest == null || rest.isBlank()) return new String[0];
        com.mojang.brigadier.StringReader reader = new com.mojang.brigadier.StringReader(rest);
        List<String> words = new ArrayList<>();
        while (reader.canRead()) {
            reader.skipWhitespace();
            if (!reader.canRead()) break;
            try {
                words.add(reader.readString());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException unreadable) {
                // The line could not be re-read even though the game already read it, so the tail is one word.
                // Losing the quoting is better than losing the argument.
                words.add(rest.substring(reader.getCursor()).trim());
                break;
            }
        }
        return words.toArray(new String[0]);
    }
}
