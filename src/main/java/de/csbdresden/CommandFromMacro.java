package de.csbdresden;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.scijava.ItemVisibility;
import org.scijava.Named;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.module.ModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.Service;
import org.scijava.ui.UIService;

import com.google.gson.Gson;

import de.csbdresden.stardist.StarDist2D;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.frame.Recorder;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;


@Plugin(type = Command.class, label = "CommandFromMacro", menuPath = "Plugins > StarDist > Other > CommandFromMacro")
public class CommandFromMacro implements Command {

    private static final List<ItemVisibility> SKIP_VISIBILITY = Arrays.asList(ItemVisibility.MESSAGE, ItemVisibility.INVISIBLE);

    @Parameter
    private String command;

    @Parameter
    private boolean process;

    @Parameter
    private String args;

    // ---------

    @Parameter
    private UIService ui;

    @Parameter
    private CommandService cmd;

    @Parameter
    private LogService log;

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
        final List<String> outputs = new ArrayList<>();

        Map<?,?> argsMap = new Gson().fromJson("{"+args+"}", Map.class);
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
                    outputs.add(key);
                } else {
                    log.warn(String.format("Ignoring argument \"%s\" since neither an input or output of this command.", key));
                }
            }
        }

        try {
            final CommandModule result = cmd.run(command, process, params).get();
            for (String name : outputs) {
                final Object output = result.getOutput(name);
                if (output != null) ui.show(output);
            }
        } catch (InterruptedException | ExecutionException e) {
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
        // System.out.println("RECORDED: " + recorded);
        final CommandInfo info = commandService.getCommand(command.getClass());
        // only proceed if this command is being recorded
        final String name = info.getMenuPath().getLeaf().getName();
        // final String cmdName = info.getLabel();
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

        // convert to json and remove curly braces
        String argsStr = new Gson().toJson(args);
        argsStr = argsStr.substring(1, argsStr.length()-1);
        // to make the macro string look nicer (otherwise have to escape all double quotes):
        // replace double quotes around json keys/values with single quotes
        // technically not correct json, but can be parsed by Gson
        final StringBuilder sb = new StringBuilder(argsStr);
        int p = 0;
        for (Entry<String, String> arg: args.entrySet()) {
            final int k = StringEscapeUtils.escapeJava(arg.getKey()).length();
            final int v = StringEscapeUtils.escapeJava(arg.getValue()).length();
            sb.replace(p, p+1, "'"); p+=1+k;
            sb.replace(p, p+1, "'"); p+=2;
            sb.replace(p, p+1, "'"); p+=1+v;
            sb.replace(p, p+1, "'"); p+=2;            
        }
        argsStr = sb.toString();
        
        final String execName = commandService.getCommand(CommandFromMacro.class).getMenuPath().getLeaf().getName();
        return String.format("run(\"%s\", \"command=[%s], process=[%s], args=[%s]\");\n",
                execName, commandClass.getName(), String.valueOf(process), argsStr);
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
