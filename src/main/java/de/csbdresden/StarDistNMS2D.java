package de.csbdresden;

import java.util.HashMap;
import java.util.List;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.UIService;
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
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

@Plugin(type = Command.class, menuPath = "Plugins > StarDist > Postprocessing > 2D NMS", label = "StarDist 2D NMS")
public class StarDistNMS2D<T extends RealType<T>> implements Command {

    @Parameter(label="Probability/Score Image")
    private Dataset prob;

    @Parameter(label="Distance Image")
    private Dataset dist;

    @Parameter(label="Label Image", type=ItemIO.OUTPUT)
    private Img<?> label;

    @Parameter(label="Probability/Score Threshold", stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double probThresh = 0.5;

    @Parameter(label="Overlap Threshold", stepSize="0.05", min="0", max="1", style=NumberWidget.SLIDER_STYLE)
    private double nmsThresh = 0.4;

    @Parameter(label="Output Type",
               choices={"ROI Manager","Label Image","Both"},
               style=ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String outputType = "ROI Manager";

    @Parameter(visibility=ItemVisibility.MESSAGE)
    private final String advMsg = "<html><u>Advanced</u></html>";

    @Parameter(label="Boundary Exclusion", stepSize="1", min="0")
    private int excludeBoundary = 2;

    @Parameter(label="Verbose", description="Verbose output")
    private boolean verbose = false;

    @Parameter
    private LogService log;

    @Parameter
    private UIService ui;
//
//    @Parameter
//    private OpService opService;
//
//    @Parameter
//    private CommandService commandService;


    private boolean exportPointRois = false;
    private boolean exportBboxRois = false;

    private RoiManager roiManager = null;
    private ImagePlus labelImage = null;
    private int labelId = 1;


    @Override
    public void run() {
        if (!checkInputs()) return;

        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> probRAI = (RandomAccessibleInterval<FloatType>) prob.getImgPlus();
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<FloatType> distRAI = (RandomAccessibleInterval<FloatType>) dist.getImgPlus();

        final long numFrames = prob.getFrames();
        final boolean isTimelapse = numFrames > 1;

        if (isTimelapse) {
            for (int t = 0; t < numFrames; t++) {
                final Candidates polygons = new Candidates(Views.hyperSlice(probRAI,2,t), Views.hyperSlice(distRAI,3,t), probThresh, excludeBoundary, verbose ? log : null);
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

        if (outputType.equals("Label Image") || outputType.equals("Both")) {
            // IJ.run(labelImage, "glasbey inverted", "");
            label = ImageJFunctions.wrap(labelImage);
        }
    }
    
    
    private boolean showError(String msg) {
        ui.showDialog(msg, MessageType.ERROR_MESSAGE);
        // log.error(msg);
        return false;
    }


    private boolean checkInputs() {
        if (!( (prob.numDimensions() == 2 && prob.axis(0).type() == Axes.X && prob.axis(1).type() == Axes.Y) ||
               (prob.numDimensions() == 3 && prob.axis(0).type() == Axes.X && prob.axis(1).type() == Axes.Y && prob.axis(2).type() == Axes.TIME) ))
            return showError("Probability/Score must be a 2D image or timelapse.");

        if (!( (dist.numDimensions() == 3 && dist.axis(0).type() == Axes.X && dist.axis(1).type() == Axes.Y && dist.axis(2).type() == Axes.CHANNEL && dist.getChannels() >= 3) ||
               (dist.numDimensions() == 4 && dist.axis(0).type() == Axes.X && dist.axis(1).type() == Axes.Y && dist.axis(2).type() == Axes.CHANNEL && dist.getChannels() >= 3 && dist.axis(3).type() == Axes.TIME) ))
            return showError("Distance must be a 2D image or timelapse with at least three channels.");

        if ((prob.numDimensions() + 1) != dist.numDimensions())
            return showError("Axes of Probability/Score and Distance not compatible.");

        if (prob.getWidth() != dist.getWidth() || prob.getHeight() != dist.getHeight())
            return showError("Width or height of Probability/Score and Distance differ.");

        if (prob.getFrames() != dist.getFrames())
            return showError("Number of frames of Probability/Score and Distance differ.");

        if (!(0 <= nmsThresh && nmsThresh < 1))
            return showError("NMS Threshold must be in interval [0,1).");

        if (excludeBoundary < 0)
            return showError("Boundary Exclusion must be >= 0");

        if (!(outputType.equals("ROI Manager") || outputType.equals("Label Image") || outputType.equals("Both")))
            return showError("Output Type must be one of {\"ROI Manager\", \"Label Image\", \"Both\"}.");

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
        case "ROI Manager":
            exportROIs(polygons, framePosition);
            break;
        case "Label Image":
            exportLabelImage(polygons, framePosition);
            break;
        case "Both":
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
