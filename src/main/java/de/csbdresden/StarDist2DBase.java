package de.csbdresden;

import java.net.URL;
import java.util.List;

import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.UIService;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;

public abstract class StarDist2DBase {
    
    @Parameter
    protected LogService log;

    @Parameter
    protected UIService ui;

    @Parameter
    protected CommandService command;

    @Parameter
    protected DatasetService dataset;
    
    // ---------
    
    protected boolean exportPointRois = false;
    protected boolean exportBboxRois = false;

    protected RoiManager roiManager = null;
    protected ImagePlus labelImage = null;
    protected int labelId = 0;
    protected long labelCount = 0;
    protected static final int MAX_LABEL_ID = 65535;
    
    // ---------
    
    protected URL getResource(final String name) {
        return StarDist2DBase.class.getClassLoader().getResource(name);
    }        
    
    protected boolean showError(String msg) {
        ui.showDialog(msg, MessageType.ERROR_MESSAGE);
        // log.error(msg);
        return false;
    }
    
    // ---------
    
    protected void export(String outputType, Candidates polygons, int framePosition) {
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
        case Opt.OUTPUT_POLYGONS:
            exportPolygons(polygons);
            break;
        default:
            showError(String.format("Unknown output type \"%s\"", outputType));
        }
    }
    
    protected void exportROIs(Candidates polygons, int framePosition) {
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
   
    protected void exportLabelImage(Candidates polygons, int framePosition) {
        if (labelImage == null)
            labelImage = createLabelImage();
        if (framePosition > 0)
            labelImage.setT(framePosition);
        final ImageProcessor ip = labelImage.getProcessor();
        final List<Integer> winner = polygons.getWinner();
        final int numWinners = winner.size();
        // winners are ordered by score -> draw from last to first to give priority to higher scores in case of overlaps
        for (int i = numWinners-1; i >= 0; i--) {
            final PolygonRoi polyRoi = Utils.toPolygonRoi(polygons.getPolygon(winner.get(i)));
            ip.setColor(1 + ((labelId + i) % MAX_LABEL_ID));
            ip.fill(polyRoi);
        }
        labelCount += numWinners;
        labelId = (labelId + numWinners) % MAX_LABEL_ID;
    }    
    
    abstract protected void exportPolygons(Candidates polygons);    
    
    abstract protected ImagePlus createLabelImage();
    
    protected Dataset labelImageToDataset(String outputType) {
        if (outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH)) {
            if (labelCount > MAX_LABEL_ID) {
                log.error(String.format("Found more than %d segments -> label image does contain some repetitive IDs.\n(\"%s\" output instead does not have this problem).", MAX_LABEL_ID, Opt.OUTPUT_ROI_MANAGER));
            }
            // IJ.run(labelImage, "glasbey inverted", "");
            final boolean isTimelapse = labelImage.getNFrames() > 1;
            final Img labelImg = (Img) ImageJFunctions.wrap(labelImage);
            final AxisType[] axes = isTimelapse ? new AxisType[]{Axes.X, Axes.Y, Axes.TIME} : new AxisType[]{Axes.X, Axes.Y};
            return Utils.raiToDataset(dataset, Opt.LABEL_IMAGE, labelImg, axes);
        } else {
            return null;
        }
        
    }
}
