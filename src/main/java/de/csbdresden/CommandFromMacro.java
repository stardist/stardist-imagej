package de.csbdresden;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
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

        final CommandInfo info = cmd.getCommand(command);
        if (info == null) {
            log.warn(String.format("Command \"%s\" not found.", command));
            return;
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


    public static String getMacroString(final Command command, final CommandInfo info, final String... outputs) {
        return getMacroString(command, info, false, outputs);
    }

    public static String getMacroString(final Command command, final CommandInfo info, final boolean process, final String... outputs) {
        final Class<?> commandClass = command.getClass();
        final Map<String,String> args = new LinkedHashMap<>();

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

        for (final String name : outputs) {
            if (info.getOutput(name) != null)
                args.put(name, "");
        }

        String argsStr = new Gson().toJson(args);
        argsStr = argsStr.substring(1, argsStr.length()-1); // remove curly braces
        argsStr = StringEscapeUtils.escapeJava(argsStr);    // escape all quotes, etc.
        return String.format("run(\"%s\", \"command=[%s], process=[%s], args=[%s]\");\n",
                CommandFromMacro.class.getSimpleName(), commandClass.getName(), String.valueOf(process), argsStr);
    }


    public static void main(final String... args) throws Exception {

        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset input = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast_crop.tif").getFile());
        ij.ui().show(input);

//        Dataset input2 = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast_timelapse.tif").getFile());
//        ij.ui().show(input2);

//        Recorder recorder = new Recorder();
//        recorder.show();

        IJ.run("CommandFromMacro", "args=[\"input\":\"yeast_crop.tif\", \"label\":\"\"], process=[false], command=[de.csbdresden.stardist.StarDist2D]");
    }


}
