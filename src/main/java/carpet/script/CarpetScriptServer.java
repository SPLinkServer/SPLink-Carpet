package carpet.script;

import carpet.script.bundled.BundledModule;
import carpet.CarpetSettings;
import carpet.CarpetServer;
import carpet.script.bundled.FileModule;
import carpet.script.bundled.Module;
import carpet.script.exception.InvalidCallbackException;
import carpet.script.value.FunctionValue;
import carpet.script.value.MapValue;
import carpet.script.value.StringValue;
import carpet.script.value.ThreadValue;
import carpet.script.value.Value;
import carpet.utils.Messenger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class CarpetScriptServer
{
    //make static for now, but will change that later:
    public final MinecraftServer server;
    public final CarpetScriptHost globalHost;
    public final Map<String, CarpetScriptHost> modules;
    public long tickStart;
    public boolean stopAll;
    private final Set<String> holyMoly;
    public final CarpetEventServer events;

    private static final List<Module> bundledModuleData = new ArrayList<>();

    public static void registerBuiltInScript(BundledModule app)
    {
        bundledModuleData.add(app);
    }

    static
    {
        registerBuiltInScript(BundledModule.carpetNative("camera", false));
        registerBuiltInScript(BundledModule.carpetNative("overlay", false));
        registerBuiltInScript(BundledModule.carpetNative("event_test", false));
        registerBuiltInScript(BundledModule.carpetNative("stats_test", false));
        registerBuiltInScript(BundledModule.carpetNative("math", true));
        registerBuiltInScript(BundledModule.carpetNative("chunk_display", false));
    }

    public CarpetScriptServer(MinecraftServer server)
    {
        this.server = server;
        ScriptHost.systemGlobals.clear();
        events = new CarpetEventServer(server);
        modules = new HashMap<>();
        tickStart = 0L;
        stopAll = false;
        holyMoly = server.getCommandManager().getDispatcher().getRoot().getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
        globalHost = CarpetScriptHost.create(this, null, false, null);
    }

    public void loadAllWorldScripts()
    {
        if (CarpetSettings.scriptsAutoload)
        {
            Messenger.m(server.getCommandSource(), "Auto-loading world scarpet apps");
            for (String moduleName: listAvailableModules(false))
            {
                addScriptHost(server.getCommandSource(), moduleName, true, true);
            }
        }

    }

    public Module getModule(String name, boolean allowLibraries)
    {
        File folder = server.getLevelStorage().resolveFile(
                server.getLevelName(), "scripts");
        File[] listOfFiles = folder.listFiles();
        if (listOfFiles != null)
            for (File script : listOfFiles)
            {
                if (script.getName().equalsIgnoreCase(name+".sc"))
                {
                    return new FileModule(script);
                }
                if (allowLibraries && script.getName().equalsIgnoreCase(name+".scl"))
                {
                    return new FileModule(script);
                }
            }
        for (Module moduleData : bundledModuleData)
        {
            if (moduleData.getName().equalsIgnoreCase(name) && (allowLibraries || !moduleData.isLibrary()))
            {
                return moduleData;
            }
        }
        return null;
    }

    public List<String> listAvailableModules(boolean includeBuiltIns)
    {
        List<String> moduleNames = new ArrayList<>();
        if (includeBuiltIns)
        {
            for (Module mi : bundledModuleData)
            {
                if (!mi.isLibrary()) moduleNames.add(mi.getName());
            }
        }
        File folder = server.getLevelStorage().resolveFile(
                server.getLevelName(), "scripts");
        File[] listOfFiles = folder.listFiles();
        if (listOfFiles == null)
            return moduleNames;
        for (File script : listOfFiles)
        {
            if (script.getName().endsWith(".sc"))
            {
                String name = script.getName().replaceFirst("\\.sc","").toLowerCase(Locale.ROOT);
                moduleNames.add(name);
            }
        }
        return moduleNames;
    }

    public ScriptHost getHostByName(String name)
    {
        if (name == null)
            return globalHost;
        return modules.get(name);
    }

    public boolean addScriptHost(ServerCommandSource source, String name, boolean perPlayer, boolean autoload)
    {
        //TODO add per player modules to support player actions better on a server
        name = name.toLowerCase(Locale.ROOT);
        Module module = getModule(name, false);
        if (module == null)
        {
            Messenger.m(source, "r Failed to add "+name+" app");
            return false;
        }
        CarpetScriptHost newHost = CarpetScriptHost.create(this, module, perPlayer, source);
        if (newHost == null)
        {
            Messenger.m(source, "r Failed to add "+name+" app");
            return false;
        }
        if (module == null)
        {
            Messenger.m(source, "r Unable to locate the app, but created empty "+name+" app instead");
            modules.put(name, newHost);
            return true;
        }
        String code = module.getCode();
        if (module.getCode() == null)
        {
            Messenger.m(source, "r Unable to load "+name+" app - not found");
            return false;
        }

        modules.put(name, newHost);

        if (autoload && !newHost.persistenceRequired)
        {
            removeScriptHost(source, name);
            return false;
        }
        //addEvents(source, name);
        addCommand(source, name);
        return true;
    }

    private void addCommand(ServerCommandSource source, String hostName)
    {
        ScriptHost host = modules.get(hostName);
        if (host == null)
        {
            return;
        }
        if (host.getFunction("__command") == null)
        {
            Messenger.m(source, "gi "+hostName+" app loaded.");
            return;
        }
        if (holyMoly.contains(hostName))
        {
            Messenger.m(source, "gi "+hostName+" app loaded with no command.");
            Messenger.m(source, "gi Tried to mask vanilla command.");
            return;
        }

        LiteralArgumentBuilder<ServerCommandSource> command = literal(hostName).
                requires((player) -> modules.containsKey(hostName)).
                executes( (c) ->
                {
                    String response = modules.get(hostName).retrieveForExecution(c.getSource()).
                            handleCommand(c.getSource(),"__command", null, "");
                    if (!response.isEmpty()) Messenger.m(c.getSource(), "gi "+response);
                    return 1;
                });

        for (String function : host.globaFunctionNames(host.main, s ->  !s.startsWith("_")).sorted().collect(Collectors.toList()))
        {
            command = command.
                    then(literal(function).
                            requires((player) -> modules.containsKey(hostName) && modules.get(hostName).getFunction(function) != null).
                            executes( (c) -> {
                                String response = modules.get(hostName).retrieveForExecution(c.getSource()).
                                        handleCommand(c.getSource(), function,null,"");
                                if (!response.isEmpty()) Messenger.m(c.getSource(),"gi "+response);
                                return 1;
                            }).
                            then(argument("args...", StringArgumentType.greedyString()).
                                    executes( (c) -> {
                                        String response = modules.get(hostName).retrieveForExecution(c.getSource()).
                                                handleCommand(c.getSource(), function,null, StringArgumentType.getString(c, "args..."));
                                        if (!response.isEmpty()) Messenger.m(c.getSource(), "gi "+response);
                                        return 1;
                                    })));
        }
        Messenger.m(source, "gi "+hostName+" app loaded with /"+hostName+" command");
        server.getCommandManager().getDispatcher().register(command);
        CarpetServer.settingsManager.notifyPlayersCommandsChanged();
    }

    public boolean removeScriptHost(ServerCommandSource source, String name)
    {
        name = name.toLowerCase(Locale.ROOT);
        if (!modules.containsKey(name))
        {
            Messenger.m(source, "r No such app found: ", "wb  " + name);
            return false;
        }
        // stop all events associated with name
        events.removeAllHostEvents(name);
        modules.get(name).onClose();
        modules.remove(name);
        CarpetServer.settingsManager.notifyPlayersCommandsChanged();
        Messenger.m(source, "gi Removed "+name+" app");
        return true;
    }

    public boolean runas(ServerCommandSource source, String hostname, FunctionValue udf, List<LazyValue> argv)
    {
        return runas(BlockPos.ORIGIN, source, hostname, udf, argv);
    }

    public boolean runas(BlockPos origin, ServerCommandSource source, String hostname, FunctionValue udf, List<LazyValue> argv)
    {
        CarpetScriptHost host = globalHost;
        try
        {
            if (hostname != null)
                host = modules.get(hostname).retrieveForExecution(source);
            host.callUDF(origin, source, udf, argv);
        }
        catch (NullPointerException | InvalidCallbackException npe)
        {
            return false;
        }
        return true;
    }

    public void tick()
    {
        events.tick();
        for (CarpetScriptHost host : modules.values())
        {
            host.tick();
        }
    }

    public void onClose()
    {
        for (ScriptHost host : modules.values())
        {
            host.onClose();
        }
        ThreadValue.shutdown();
    }
}
