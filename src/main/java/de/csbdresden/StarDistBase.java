package de.csbdresden;

import java.net.URL;

import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.UIService;

import net.imagej.DatasetService;

public class StarDistBase {
    
    @Parameter
    protected LogService log;

    @Parameter
    protected UIService ui;

    @Parameter
    protected CommandService command;

    @Parameter
    protected DatasetService dataset;
    
    
    protected URL getLogoUrl() {
        return StarDistBase.class.getClassLoader().getResource("images/logo.png");
    }    
    
    protected boolean showError(String msg) {
        ui.showDialog(msg, MessageType.ERROR_MESSAGE);
        // log.error(msg);
        return false;
    }
    

}
