package de.csbdresden;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > Other > StarDist 2D NMS (postprocessing only)", label = "StarDist 2D NMS")
public class StarDistNMS2D extends StarDistBase implements Command {
    
    @Parameter(label=Opt.PROB_IMAGE)
    private Dataset prob;

    @Parameter(label=Opt.DIST_IMAGE)
    private Dataset dist;

    @Parameter(label=Opt.LABEL_IMAGE, type=ItemIO.OUTPUT)
    private Dataset label;

    @Parameter(label=Opt.PROB_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);

    @Parameter(label=Opt.NMS_THRESH, stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);

    @Parameter(label=Opt.OUTPUT_TYPE, choices={Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH}, style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
    
    // ---------

    @Parameter(visibility=ItemVisibility.MESSAGE)
    private final String advMsg = "<html><u>Advanced</u></html>";

    @Parameter(label=Opt.EXCLUDE_BNDRY, min="0", stepSize="1")
    private int excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);

    @Parameter(label=Opt.VERBOSE)
    private boolean verbose = (boolean) Opt.getDefault(Opt.VERBOSE);

    @Parameter(label=Opt.RESTORE_DEFAULTS, callback="restoreDefaults")
    private Button restoreDefaults;
    
    // ---------

    private boolean exportPointRois = false;
    private boolean exportBboxRois = false;

    private RoiManager roiManager = null;
    private ImagePlus labelImage = null;
    private int labelId = 1;
    
    // ---------
    
    private void restoreDefaults() {
        probThresh = (double) Opt.getDefault(Opt.PROB_THRESH);
        nmsThresh = (double) Opt.getDefault(Opt.NMS_THRESH);
        outputType = (String) Opt.getDefault(Opt.OUTPUT_TYPE);
        excludeBoundary = (int) Opt.getDefault(Opt.EXCLUDE_BNDRY);
        verbose = (boolean) Opt.getDefault(Opt.VERBOSE);
    }

    // ---------

    @Override
    public void run() {
        if (!checkInputs()) return;

        final RandomAccessibleInterval<FloatType> probRAI = (RandomAccessibleInterval<FloatType>) prob.getImgPlus();
        final RandomAccessibleInterval<FloatType> distRAI = (RandomAccessibleInterval<FloatType>) dist.getImgPlus();
        
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);
        final boolean isTimelapse = probAxes.contains(Axes.TIME);

        if (isTimelapse) {
            final int probTimeDim = IntStream.range(0, probAxes.size()).filter(d -> prob.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final int distTimeDim = IntStream.range(0, distAxes.size()).filter(d -> dist.axis(d).type() == Axes.TIME).findFirst().getAsInt();
            final long numFrames = prob.getFrames();

            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI, probTimeDim, t), Views.hyperSlice(distRAI, distTimeDim, t), probThresh, excludeBoundary, verbose ? log : null);
                polygons.nms(nmsThresh);
                if (verbose)
                    log.info(String.format("frame %03d: %d polygon candidates, %d remain after non-maximum suppression\n", t, polygons.getSorted().size(), polygons.getWinner().size()));
                export(polygons, 1+t);
            }
        } else {
            final Candidates polygons = new Candidates(probRAI, distRAI, probThresh, excludeBoundary, verbose ? log : null);
            polygons.nms(nmsThresh);
            if (verbose)
                log.info(String.format("%d polygon candidates, %d remain after non-maximum suppression\n", polygons.getSorted().size(), polygons.getWinner().size()));
            export(polygons, 0);
        }

        if (outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH)) {
            if (labelId-1 > 65535) {
                log.error(String.format("Found too many segments, label image is not correct. Use \"%s\" output instead.", Opt.OUTPUT_ROI_MANAGER));
            }
            // IJ.run(labelImage, "glasbey inverted", "");
            final Img labelImage_ = (Img) ImageJFunctions.wrap(labelImage);            
            // https://forum.image.sc/t/convert-randomaccessibleinterval-to-imgplus-or-dataset/8535/6
            final AxisType[] axisTypes = isTimelapse ? new AxisType[]{ Axes.X, Axes.Y, Axes.TIME } : new AxisType[]{ Axes.X, Axes.Y };
            label = dataset.create(new ImgPlus(dataset.create(labelImage_), Opt.LABEL_IMAGE, axisTypes));            
        }
    }
    

    private boolean checkInputs() {
        final LinkedHashSet<AxisType> probAxes = Utils.orderedAxesSet(prob);
        final LinkedHashSet<AxisType> distAxes = Utils.orderedAxesSet(dist);
        
        if (!( (prob.numDimensions() == 2 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y))) ||
               (prob.numDimensions() == 3 && probAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.TIME))) ))
            return showError("Probability/Score must be a 2D image or timelapse.");

        if (!( (dist.numDimensions() == 3 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL))            && dist.getChannels() >= 3) ||
               (dist.numDimensions() == 4 && distAxes.containsAll(Arrays.asList(Axes.X, Axes.Y, Axes.CHANNEL, Axes.TIME)) && dist.getChannels() >= 3) ))
            return showError("Distance must be a 2D image or timelapse with at least three channels.");

        if ((prob.numDimensions() + 1) != dist.numDimensions())
            return showError("Axes of Probability/Score and Distance not compatible.");

        if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
            return showError("Width or height of Probability/Score and Distance differ.");

        if (prob.getFrames() != dist.getFrames())
            return showError("Number of frames of Probability/Score and Distance differ.");
        
        AxisType[] probAxesArray = probAxes.toArray(new AxisType[0]);
        AxisType[] distAxesArray = distAxes.toArray(new AxisType[0]);
        if (!( probAxesArray[0] == Axes.X && probAxesArray[1] == Axes.Y ))
            return showError("First two axes of Probability/Score must be a X and Y.");
        if (!( distAxesArray[0] == Axes.X && distAxesArray[1] == Axes.Y ))
            return showError("First two axes of Distance must be a X and Y.");        

        if (!(0 <= nmsThresh && nmsThresh < 1))
            return showError("NMS Threshold must be in interval [0,1).");

        if (excludeBoundary < 0)
            return showError("Boundary Exclusion must be >= 0");

        if (!(outputType.equals(Opt.OUTPUT_ROI_MANAGER) || outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH)))
            return showError(String.format("Output Type must be one of {\"%s\", \"%s\", \"%s\"}.", Opt.OUTPUT_ROI_MANAGER, Opt.OUTPUT_LABEL_IMAGE, Opt.OUTPUT_BOTH));

        if (verbose) {
            log.info(String.format("probThresh = %f\n", probThresh));
            log.info(String.format("nmsThresh = %f\n", nmsThresh));
            log.info(String.format("excludeBoundary = %d\n", excludeBoundary));
            log.info(String.format("verbose = %s\n", verbose));
            log.info(String.format("outputType = %s\n", outputType));
        }
        
        return true;
    }


    private void export(Candidates polygons, int framePosition) {
        switch (outputType) {
        case Opt.OUTPUT_ROI_MANAGER:
            exportROIs(polygons, framePosition);
            break;
        case Opt.OUTPUT_LABEL_IMAGE:
            exportLabelImage(polygons, framePosition);
            break;
        case Opt.OUTPUT_BOTH:
            exportROIs(polygons, framePosition);
            exportLabelImage(polygons, framePosition);
            break;
        default:
            showError(String.format("Unknown output type \"%s\"", outputType));
        }
    }


    private void exportROIs(Candidates polygons, int framePosition) {
        if (roiManager == null) {
            IJ.run("ROI Manager...", "");
            roiManager = RoiManager.getInstance();
            roiManager.reset(); // clear all rois
        }
        for (final int i : polygons.getWinner()) {
            final PolygonRoi polyRoi = Utils.toPolygonRoi(polygons.getPolygon(i));
            // if (framePosition > 0) polyRoi.setPosition(0, 0, framePosition);
            if (framePosition > 0) polyRoi.setPosition(framePosition);
            roiManager.addRoi(polyRoi);
            if (exportPointRois) {
                final Point2D o = polygons.getOrigin(i);
                final PointRoi pointRoi = new PointRoi(o.x, o.y);
                // if (framePosition > 0) pointRoi.setPosition(0, 0, framePosition);
                if (framePosition > 0) pointRoi.setPosition(framePosition);
                roiManager.addRoi(pointRoi);
            }
            if (exportBboxRois) {
                final Box2D bbox = polygons.getBbox(i);
                final Roi bboxRoi = new Roi(bbox.xmin, bbox.ymin, bbox.xmax - bbox.xmin, bbox.ymax - bbox.ymin);
                // if (framePosition > 0) bboxRoi.setPosition(0, 0, framePosition);
                if (framePosition > 0) bboxRoi.setPosition(framePosition);
                roiManager.addRoi(bboxRoi);
            }
        }
    }


    private void exportLabelImage(Candidates polygons, int framePosition) {
        if (labelImage == null)
            labelImage = IJ.createImage("Labeling", "16-bit black", (int)prob.getWidth(), (int)prob.getHeight(), 1, 1, (int)prob.getFrames());
        if (prob.getFrames() > 1)
            labelImage.setT(framePosition);
        final ImageProcessor ip = labelImage.getProcessor();
        final List<Integer> winner = polygons.getWinner();
        final int numWinners = winner.size();
        // winners are ordered by score -> draw from last to first to give priority to higher scores in case of overlaps
        for (int i = numWinners-1; i >= 0; i--) {
            final PolygonRoi polyRoi = Utils.toPolygonRoi(polygons.getPolygon(winner.get(i)));
            ip.setColor(labelId+i);
            ip.fill(polyRoi);
        }
        labelId += numWinners;
    }


    public static void main(final String... args) throws Exception {
        final ImageJ ij = new ImageJ();
        ij.launch(args);

        Dataset prob = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_prob.tif").getFile());
        Dataset dist = ij.scifio().datasetIO().open(StarDistNMS2D.class.getClassLoader().getResource("blobs_dist.tif").getFile());

        ij.ui().show(prob);
        ij.ui().show(dist);

        final HashMap<String, Object> params = new HashMap<>();
        params.put("prob", prob);
        params.put("dist", dist);
        ij.command().run(StarDistNMS2D.class, true, params);

        IJ.run("Tile");
    }

}
