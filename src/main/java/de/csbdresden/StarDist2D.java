package de.csbdresden;

import static de.csbdresden.StarDistModel.MODELS;
import static de.csbdresden.StarDistModel.MODEL_DSB2018_V1;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;

import de.csbdresden.csbdeep.commands.GenericNetwork;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > StarDist 2D")
public class StarDist2D extends StarDistBase implements Command {

    @Parameter(label="", visibility=ItemVisibility.MESSAGE)
    private final String msgTitle = "<html>" +
            "<table><tr valign='top'><td>" +
            "<h2>Object Detection with Star-convex Shapes</h2>" +
            "<a href='https://github.com/mpicbg-csbd/stardist'>https://github.com/mpicbg-csbd/stardist</a>" +
            "<br/><br/><small>Please cite our paper if StarDist was helpful for your research. Thanks!</small>" +
            "</td><td>&nbsp;&nbsp;<img src='"+getLogoUrl()+"' width='100' height='100'></img><td>" +
            "</tr></table>" +
            "</html>";

    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><b>Neural Network Prediction</b></html>")
    private final String predMsg = "<html><hr width='100'></html>";

    @Parameter(label=Opt.INPUT_IMAGE) //, autoFill=false)
    private Dataset input;

    @Parameter(label=Opt.MODEL,
               choices={MODEL_DSB2018_V1,
                        Opt.MODEL_FILE,
                        Opt.MODEL_URL}, style=ChoiceWidget.LIST_BOX_STYLE)
    private String modelChoice = (String) Opt.getDefault(Opt.MODEL);

    @Parameter(label=Opt.NORMALIZE_IMAGE)
    private boolean normalizeInput = (boolean) Opt.getDefault(Opt.NORMALIZE_IMAGE);

    @Parameter(label=Opt.PERCENTILE_LOW, stepSize="0.1", min="0", max="100", style=NumberWidget.SLIDER_STYLE, callback="percentileBottomChanged")
    private double percentileBottom = (double) Opt.getDefault(Opt.PERCENTILE_LOW);

    @Parameter(label=Opt.PERCENTILE_HIGH, stepSize="0.1", min="0", max="100", style=NumberWidget.SLIDER_STYLE, callback="percentileTopChanged")
    private double percentileTop = (double) Opt.getDefault(Opt.PERCENTILE_HIGH);

    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><br/><b>NMS Postprocessing</b></html>")
    private final String nmsMsg = "<html><br/><hr width='100'></html>";

    @Parameter(label=Opt.LABEL_IMAGE, type=ItemIO.OUTPUT)
    private Dataset label;

    @Parameter(label=Opt.PROB_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);

    @Parameter(label=Opt.NMS_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);

    @Parameter(label=Opt.OUTPUT_TYPE, choices={Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH}, style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);

    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE, label="<html><br/><b>Advanced Options</b></html>")
    private final String advMsg = "<html><br/><hr width='100'></html>";

    @Parameter(label=Opt.MODEL_FILE, required=false)
    private File modelFile;

    @Parameter(label=Opt.MODEL_URL, required=false)
    protected String modelUrl;

    @Parameter(label=Opt.NUM_TILES, min="1", stepSize="1")
    private int nTiles = (int) Opt.getDefault(Opt.NUM_TILES);

    @Parameter(label=Opt.EXCLUDE_BNDRY, min="0", stepSize="1")
    private int excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);

    @Parameter(label=Opt.VERBOSE)
    private boolean verbose = (boolean) Opt.getDefault(Opt.VERBOSE);

    @Parameter(label=Opt.RESTORE_DEFAULTS, callback="restoreDefaults")
    private Button restoreDefaults;

    // ---------

    private void restoreDefaults() {
        modelChoice = (String) Opt.getDefault(Opt.MODEL);
        normalizeInput = (boolean) Opt.getDefault(Opt.NORMALIZE_IMAGE);
        percentileBottom = (double) Opt.getDefault(Opt.PERCENTILE_LOW);
        percentileTop = (double) Opt.getDefault(Opt.PERCENTILE_HIGH);
        probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);
        nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);
        outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
        nTiles = (int) Opt.getDefault(Opt.NUM_TILES);
        excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);
        verbose = (boolean) Opt.getDefault(Opt.VERBOSE);
    }

    private void percentileBottomChanged() {
        percentileTop = Math.max(percentileBottom, percentileTop);
    }

    private void percentileTopChanged() {
        percentileBottom = Math.min(percentileBottom, percentileTop);
    }

    // ---------

    @Override
    public void run() {
        if (!checkInputs()) return;

        // TODO: timelapse support

        File tmpModelFile = null;
        try {
            final HashMap<String, Object> paramsCNN = new HashMap<>();
            paramsCNN.put("input", input);
            paramsCNN.put("normalizeInput", normalizeInput);
            paramsCNN.put("percentileBottom", percentileBottom);
            paramsCNN.put("percentileTop", percentileTop);
            paramsCNN.put("clip", false);
            paramsCNN.put("nTiles", nTiles);
            paramsCNN.put("blockMultiple", 64);
            paramsCNN.put("overlap", 64);
            paramsCNN.put("batchSize", 1);
            paramsCNN.put("showProgressDialog", true);

            switch (modelChoice) {
            case Opt.MODEL_FILE:
                paramsCNN.put("modelFile", modelFile);
                break;
            case Opt.MODEL_URL:
                paramsCNN.put("modelUrl", modelUrl);
                break;
            default:
                StarDistModel pretrainedModel = MODELS.get(modelChoice);
                if (pretrainedModel.canGetFile()) {
                    File file = pretrainedModel.getFile();
                    paramsCNN.put("modelFile", file);
                    if (pretrainedModel.isTempFile())
                        tmpModelFile = file;
                } else {
                    paramsCNN.put("modelUrl", pretrainedModel.url);
                }
            }

            final Future<CommandModule> futureCNN = command.run(GenericNetwork.class, false, paramsCNN);
            final RandomAccessibleInterval<FloatType> prediction = (RandomAccessibleInterval<FloatType>) futureCNN.get().getOutput("output");

            final long[] shape = Intervals.dimensionsAsLongArray(prediction);
            final RandomAccessibleInterval<FloatType> probRAI = Views.hyperSlice(prediction, 2, 0);
            final RandomAccessibleInterval<FloatType> distRAI = Views.offsetInterval(prediction, new long[]{0,0,1}, new long[]{shape[0],shape[1],shape[2]-1});

            // is there a better way?
            // https://forum.image.sc/t/convert-randomaccessibleinterval-to-imgplus-or-dataset/8535/6
            final Dataset prob = dataset.create(new ImgPlus(dataset.create(probRAI), "prob", new AxisType[] { Axes.X, Axes.Y }));
            final Dataset dist = dataset.create(new ImgPlus(dataset.create(distRAI), "dist", new AxisType[] { Axes.X, Axes.Y, Axes.CHANNEL }));

            // ui.show(prob);
            // ui.show(dist);

            final HashMap<String, Object> paramsNMS = new HashMap<>();
            paramsNMS.put("prob", prob);
            paramsNMS.put("dist", dist);
            paramsNMS.put("probThresh", probThresh);
            paramsNMS.put("nmsThresh", nmsThresh);
            paramsNMS.put("outputType", outputType);
            paramsNMS.put("excludeBoundary", excludeBoundary);
            paramsNMS.put("verbose", verbose);

            final Future<CommandModule> futureNMS = command.run(StarDistNMS2D.class, false, paramsNMS);
            label = (Dataset) futureNMS.get().getOutput("label");

        } catch (InterruptedException | ExecutionException | IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (tmpModelFile != null && tmpModelFile.exists())
                    tmpModelFile.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    private boolean checkInputs() {
        if (!( (input.numDimensions() == 2 && input.axis(0).type() == Axes.X && input.axis(1).type() == Axes.Y) ||
               (input.numDimensions() == 3 && input.axis(0).type() == Axes.X && input.axis(1).type() == Axes.Y && input.axis(2).type() == Axes.TIME) ||
               (input.numDimensions() == 3 && input.axis(0).type() == Axes.X && input.axis(1).type() == Axes.Y && input.axis(2).type() == Axes.CHANNEL) ||
               (input.numDimensions() == 4 && input.axis(0).type() == Axes.X && input.axis(1).type() == Axes.Y && input.axis(2).type() == Axes.CHANNEL && input.axis(3).type() == Axes.TIME) ))
            return showError("Input must be a 2D image or timelapse (with or without channels).");
        
        if (!( modelChoice.equals(Opt.MODEL_FILE) || modelChoice.equals(Opt.MODEL_URL) || MODELS.containsKey(modelChoice) )) 
            return showError(String.format("Unsupported Model \"%s\".", modelChoice));
        
        return true;
    }


    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset input = ij.scifio().datasetIO().open(StarDist2D.class.getClassLoader().getResource("yeast.tif").getFile());
        ij.ui().show(input);

        final HashMap<String, Object> params = new HashMap<>();
        ij.command().run(StarDist2D.class, true, params);
    }

}
