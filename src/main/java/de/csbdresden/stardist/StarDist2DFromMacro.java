package de.csbdresden.stardist;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import net.imagej.Data;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import org.ojalgo.series.primitive.DataSeries;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static de.csbdresden.stardist.StarDist2DModel.MODEL_DSB2018_HEAVY_AUGMENTATION;
import static de.csbdresden.stardist.StarDist2DModel.MODEL_DSB2018_PAPER;
import static org.apache.log4j.helpers.Loader.getResource;

/**
 * This class serves calls from the ImageJ Macro language. It's just a mediator which calls the real StartDist2D plugin.
 *
 * @author: haesleinhuepf
 * March 2020
 */
@Plugin(type = Command.class, menu = {
        @org.scijava.plugin.Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC),
        @org.scijava.plugin.Menu(label = "StarDist"),
        @org.scijava.plugin.Menu(label = "Other"),
        @Menu(label = "StarDist 2D (from Macro)", weight = 2)})
public class StarDist2DFromMacro implements Command {

    @Parameter
    private CommandService commandService;

    @Parameter
    private UIService uiService;

    @Parameter
    private Dataset input;

/* // It really doesn't work with scijava dialogs :-(
    @Parameter
    private String modelChoice = (String) Opt.getDefault(Opt.MODEL);

    @Parameter
    private boolean normalizeInput = (boolean) Opt.getDefault(Opt.NORMALIZE_IMAGE);

    @Parameter
    private double percentileBottom = (double) Opt.getDefault(Opt.PERCENTILE_LOW);

    @Parameter
    private double percentileTop = (double) Opt.getDefault(Opt.PERCENTILE_HIGH);

    @Parameter
    private double probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);

    @Parameter
    private double nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);

    @Parameter
    private String outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);

    @Parameter(required=false)
    private String modelFile = "";

    @Parameter(required=false)
    protected String modelUrl = "";

    @Parameter
    private int nTiles = (int) Opt.getDefault(Opt.NUM_TILES);

    @Parameter
    private int excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);

    @Parameter
    private boolean verbose = (boolean) Opt.getDefault(Opt.VERBOSE);

    @Parameter
    private boolean showCsbdeepProgress = (boolean) Opt.getDefault(Opt.CSBDEEP_PROGRESS_WINDOW);

    @Parameter
    private boolean showProbAndDist = (boolean) Opt.getDefault(Opt.SHOW_PROB_DIST);
*/


    @Override
    public void run() {
        // Build an ImageJ1 generic dialog
        String modelChoice = (String) Opt.getDefault(Opt.MODEL);
        boolean normalizeInput = (boolean) Opt.getDefault(Opt.NORMALIZE_IMAGE);
        double percentileBottom = (double) Opt.getDefault(Opt.PERCENTILE_LOW);
        double percentileTop = (double) Opt.getDefault(Opt.PERCENTILE_HIGH);
        double probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);
        double nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);
        String outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
        String modelFile = "";
        String modelUrl = "";
        int nTiles = (int) Opt.getDefault(Opt.NUM_TILES);
        int excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);
        boolean verbose = (boolean) Opt.getDefault(Opt.VERBOSE);
        boolean showCsbdeepProgress = (boolean) Opt.getDefault(Opt.CSBDEEP_PROGRESS_WINDOW);
        boolean showProbAndDist = (boolean) Opt.getDefault(Opt.SHOW_PROB_DIST);

        GenericDialog gd = new GenericDialog("StarDist2D (From Macro)");
        gd.addMessage("This plugin is thought for being called via ImageJ scripting only. Use 'StarDist 2D' instead.");

        gd.addStringField("modelChoice", modelChoice);
        gd.addCheckbox("normalizeInput", normalizeInput);
        gd.addNumericField("percentileBottom", percentileBottom, 3);
        gd.addNumericField("percentileTop", percentileTop, 3);
        gd.addNumericField("probThresh", probThresh, 3);
        gd.addNumericField("nmsThresh", nmsThresh, 3);
        gd.addStringField("outputType", outputType);
        gd.addStringField("modelFile", modelFile);
        gd.addStringField("modelUrl", modelUrl);
        gd.addNumericField("nTiles", nTiles, 0);
        gd.addNumericField("excludeBoundary", excludeBoundary, 0);
        gd.addCheckbox("verbose", verbose);
        gd.addCheckbox("showCsbdeepProgress", showCsbdeepProgress);
        gd.addCheckbox("showProbAndDist", showProbAndDist);

        gd.showDialog();

        if (gd.wasCanceled()) {
            return;
        }

        modelChoice = gd.getNextString();
        normalizeInput = gd.getNextBoolean();
        percentileBottom = gd.getNextNumber();
        percentileTop = gd.getNextNumber();
        probThresh = gd.getNextNumber();
        nmsThresh =  gd.getNextNumber();
        outputType = gd.getNextString();
        modelFile = gd.getNextString();
        modelUrl = gd.getNextString();
        nTiles = (int)gd.getNextNumber();
        excludeBoundary = (int)gd.getNextNumber();
        verbose = gd.getNextBoolean();
        showCsbdeepProgress = gd.getNextBoolean();
        showProbAndDist = gd.getNextBoolean();

        System.out.println("normalizeInput was "+ normalizeInput);

        Future module = commandService.run(StarDist2D.class, false,
                "input", input,
                "modelChoice", modelChoice,
                "modelFile", (modelFile!=null?new File(modelFile):""),
                "modelUrl",  (modelUrl!=null?modelUrl:""),
                "normalizeInput", new Boolean(normalizeInput),
                "percentileBottom", percentileBottom,
                "percentileTop", percentileTop,
                "probThresh", probThresh,
                "nmsThresh", nmsThresh,
                "outputType", outputType,
                "nTiles", nTiles,
                "excludeBoundary", excludeBoundary,
                "verbose", verbose,
                "showCsbdeepProgress", showCsbdeepProgress,
                "showProbAndDist", showProbAndDist
        );

        CommandModule res = null;

        try {
            res = (CommandModule) module.get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        System.out.println("RES: " + res.toString());
        System.out.println("RES class: " + res.getClass());

        Dataset label = (Dataset) res.getOutput("label");
        uiService.show(label);
    }
}
