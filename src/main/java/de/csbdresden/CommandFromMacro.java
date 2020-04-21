package de.csbdresden;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.ClassUtils;
import org.scijava.Context;
import org.scijava.ItemVisibility;
import org.scijava.Named;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.display.DisplayPostprocessor;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleException;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.Service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import de.csbdresden.stardist.StarDist2D;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.Recorder;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;


@Plugin(type = Command.class, label = "Command From Macro", menuPath = "Plugins > StarDist > Other > Command From Macro")
public class CommandFromMacro implements Command {

    private static final List<ItemVisibility> SKIP_VISIBILITY = Arrays.asList(ItemVisibility.MESSAGE, ItemVisibility.INVISIBLE);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Parameter
    private String command;

    @Parameter
    private String args;

    @Parameter
    private boolean process;

    // ---------

    @Parameter
    private CommandService cmd;

    @Parameter
    private LogService log;

    @Parameter
    private Context context;
    
    // ---------

    @Override
    public void run() {

        CommandInfo info = cmd.getCommand(command);
        if (info == null) {
            for (CommandInfo c: cmd.getCommands()) {
                try {
                    if (command.equals(c.getMenuPath().getLeaf().getName())) {
                        info = c;
                        command = info.getClassName();
                        break;
                    }
                } catch (NullPointerException e) {}
            }
            if (info == null) {
                log.error(String.format("Command \"%s\" not found.", command));
                return;
            }
        }

        final Map<String,Object> params = new LinkedHashMap<>();

        Map<?,?> argsMap = GSON.fromJson("{"+args+"}", Map.class);
        // System.out.println(argsMap);

        for (Object keyO : argsMap.keySet()) {
            final String key = String.valueOf(keyO);
            final String value = String.valueOf(argsMap.get(keyO));
            ModuleItem<?> item = null;

            item = info.getInput(key);
            if (item != null) {
                Class<?> clazz = item.getType();
                if (clazz.isPrimitive())
                    clazz = ClassUtils.primitiveToWrapper(clazz);
                params.put(key, toParameter(value, clazz));
            } else {
                item = info.getOutput(key);
                if (item != null) {
                    log.info(String.format("No need to specify output argument \"%s\".", key));
                } else {
                    log.warn(String.format("Ignoring argument \"%s\" since not an input.", key));
                }
            }
        }
        
        try {
            // run command with parsed parameters
            final CommandModule result = cmd.run(command, process, params).get();

            // show outputs
            final Module module = ((ModuleInfo)info).createModule();
            for (Entry<String,Object> e : result.getOutputs().entrySet())
                module.setOutput(e.getKey(), e.getValue());
            DisplayPostprocessor display = new DisplayPostprocessor();
            display.setContext(context);
            display.process(module);
            
        } catch (InterruptedException | ExecutionException | ModuleException e) {
            e.printStackTrace();
        }
    }


    private Object toParameter(final String value, final Class<?> clazz) {
        // some special classes (TODO: incomplete)
        if (clazz == String.class)
            return value;
        if (clazz == File.class)
            return new File(value);
        // all typical number types and boolean are covered by this
        if (clazz.getName().startsWith("java.lang.")) {
            try {
                return clazz.getDeclaredMethod("valueOf", String.class).invoke(null, value);
            } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
        // ImagePlus and typical imagej2 image types (all that implement RAI)
        if (clazz == ImagePlus.class || RandomAccessibleInterval.class.isAssignableFrom(clazz)) {
            final ImagePlus imp = WindowManager.getImage(value);
            if (imp == null)
                log.error(String.format("Could not find input image with name/title \"%s\".", value));
            return imp;
        }
        log.error(String.format("Cannot process arguments of class \"%s\".", clazz.getName()));
        return null;
    }
    
    
    public static boolean record(final Command command, final CommandService commandService) {
        return record(command, commandService, false);
    }    
    
    public static boolean record(final Command command, final CommandService commandService, final boolean process) {
        if (Recorder.getInstance() == null)
            return false;
        final String recorded = Recorder.getCommand();
        final CommandInfo info = commandService.getCommand(command.getClass());
        // only proceed if this command is being recorded
        final String name = info.getMenuPath().getLeaf().getName();
        // System.out.printf("RECORDED: %s, COMMAND: %s\n", recorded, name);
        if (recorded==null || !recorded.equals(name))
            return false;
        // prevent automatic recording
        Recorder.setCommand(null);
        // record manually
        Recorder.recordString(getMacroString(command, commandService, process));
        return true;
    }
    
    
    private static String getMacroString(final Command command, final CommandService commandService, final boolean process) {
        final Class<?> commandClass = command.getClass();
        final CommandInfo info = commandService.getCommand(command.getClass());
        final Map<String,String> args = new LinkedHashMap<>();

        // add input parameters as arguments
        for (final ModuleItem<?> item : info.inputs()) {
            final String name = item.getName();
            final Class<?> clazz = item.getType();
            if (SKIP_VISIBILITY.contains(item.getVisibility()) || // skip items that shouldn't be recorded
                Service.class.isAssignableFrom(clazz))            // skip all (injected) services
                continue;
            try {
                final Field field = commandClass.getDeclaredField(name);
                if (!field.isAccessible()) field.setAccessible(true);
                final Object value = field.get(command);
                // skip unassigned items (includes buttons)
                if (value == null)
                    continue;
                if (Named.class.isAssignableFrom(clazz)) // ImgPlus and Dataset
                    args.put(name, ((Named)value).getName());
                else if (clazz == ImagePlus.class)
                    args.put(name, ((ImagePlus)value).getTitle());
                else
                    args.put(name, String.valueOf(value));
            } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        /*
        // designate assigned outputs to be shown
        for (final ModuleItem<?> item : info.outputs()) {
            final String name = item.getName();
            try {
                final Field field = commandClass.getDeclaredField(name);
                if (!field.isAccessible()) field.setAccessible(true);
                final Object value = field.get(command);
                // skip unassigned outputs
                if (value == null)
                    continue;
                args.put(name, "");
            } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        */

        // manually build json dict string, replacing double quotes around
        // keys/values with single quotes to make the macro string look nicer
        // technically not correct json, but can be parsed by Gson
        final StringBuilder sb = new StringBuilder();
        for (Entry<String, String> arg: args.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String k = GSON.toJson(arg.getKey());   k = k.substring(1, k.length()-1);
            String v = GSON.toJson(arg.getValue()); v = v.substring(1, v.length()-1);
            // double escape backslashes, otherwise imagej will unescape them and lead to a json parse error 
            if (v.contains("\\\\")) v = v.replaceAll("\\\\", "\\\\\\\\");
            sb.append(String.format("'%s':'%s'", k, v));
        }
        final String execName = commandService.getCommand(CommandFromMacro.class).getMenuPath().getLeaf().getName();
        return String.format("run(\"%s\", \"command=[%s], args=[%s], process=[%s]\");\n",
                execName, commandClass.getName(), sb.toString(), String.valueOf(process));
    }


    public static void main(final String... args) throws Exception {

        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset input = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast_crop.tif").getFile());
        ij.ui().show(input);

//        Dataset input2 = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast_timelapse.tif").getFile());
//        ij.ui().show(input2);

        Recorder recorder = new Recorder();
        recorder.show();
        
        final Map<String, Object> params = new LinkedHashMap<>();
        // params.put("input", input);
        ij.command().run(StarDist2D.class, true, params);
        

//        IJ.run("CommandFromMacro", "args=[\"input\":\"yeast_crop.tif\", \"label\":\"\"], process=[false], command=[de.csbdresden.stardist.StarDist2D]");
    }


}
