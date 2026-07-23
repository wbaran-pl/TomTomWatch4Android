/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.studioblueplanet.tomtomwatch;

import net.studioblueplanet.generics.ToolBox;
import net.studioblueplanet.usb.UsbFile;
import net.studioblueplanet.logger.DebugLogger;
import net.studioblueplanet.settings.ConfigSettings;
import hirondelle.date4j.DateTime;

import java.util.ArrayList;

/**
 * View for the TomTomWatch application. Supports Multisport,
 * Spark/Runner and Adventurer
 *
 * @author Jorgen
 */
public abstract class TomTomWatchView
{
    protected final CommunicationProcess communicationProcess;

    /**
     * Constructor. Creates new form TomTomWatchView
     * @param communicationProcess The process for communication to the watch
     */
    public TomTomWatchView(CommunicationProcess communicationProcess)
    {
        // Get the application settings
        ConfigSettings settings = ConfigSettings.getInstance();

        // Disable TLS certificate validation
        if (settings.getBooleanValue("tlsCerficateCheckDisable"))
        {
            ToolBox.disableCertificateValidation();
        }

        // Set the DebugLogger log level
        DebugLogger.setDebugLevel(settings.getStringValue("debugLevel"));

        // Get the communication process. This starts the process
        this.communicationProcess = communicationProcess;
    }

    /**
     * This method pushes a command on the command stack. First however a check
     * is made whether a watch is connected
     * @param command The command to push.
     */
    public void checkAndPushCommand(ThreadCommand command)
    {
        if (communicationProcess.isConnected())
        {
            communicationProcess.pushCommand(command);
        }
        else
        {
            showErrorDialog("No watch connected");
        }
        
    }
    
    /**
     * Returns the activity description to be shown in the list box. Format is:
     * [v] - [start datetime] [id] - [type] [activity]
     * [v]              - 'v': File has been saved, 'x': file has not been saved
     * [start datetime] - Start of the activity
     * [id]             - 0x99999999, file ID
     * [type]           - 'file' for loaded from file, 'watch' for downloaded from watch
     * [activity]       - the activity: 'run', 'swim' etc
     * @param data Data defining the activity
     * @param prefix Prefix indicating the source of the activity ('file' or 'watch')
     * @return A string with the description
     */
    public static String getActivityDescription(ActivityData data, String prefix)
    {
        String description;
        String fitnessPointsString;

        if (data.ttbinSaved)
        {
            description = "v ";
        } else
        {
            description = "x ";
        }
        String dateTime        = data.activity.getStartDateTime().format("YYYY-MM-DD hh:mm");
        int fitnessPoints   =data.activity.getFitnessPoints();
        if (fitnessPoints>=0)
        {
            fitnessPointsString=Integer.toString(fitnessPoints);
        }
        else
        {
            fitnessPointsString="-";
        }
        description += dateTime
                + " - " + prefix
                + String.format("- %-13s ", data.activity.getActivityDescription())
                + String.format("- %5.1f km ", data.activity.getDistance())
                + " - " + fitnessPointsString;

        return description;
    }

    /**
     * Show the track for the selected activity
     * @param data Activity data
     */
    public abstract void showTrack(ActivityData data);

    /**
     * Show the track for the selected route
     * @param route Route data
     */
    public abstract void showTrack(Route route);

    public abstract boolean isDownloadMostRecent();
    public abstract void clear();
    public abstract void showErrorDialog(String message);
    public abstract void showWarningDialog(String message);
    public abstract void showInfoDialog(String message);
    public abstract boolean showConfirmDialog(String message);
    public abstract void clearRoutes();
    public abstract void showTime(DateTime time);
    public abstract void setProgress(int promile);
    public abstract void setStatus(String status);
    public abstract void appendStatus(String status);
    public abstract boolean isAutoSaveTtbin();
    public abstract void addListItem(ActivityData data, String prefix);
    public abstract void selectFirstListIndex();
    public abstract void selectLastListIndex();
    public abstract void setDeviceName(String deviceName);
    public abstract void setFirmwareVersion(String version);
    public abstract void setSerial(String serial);
    public abstract void setProductId(int id);
    public abstract void addRoutesToListBox(ArrayList<UsbFile> routes, int index);
    public abstract void addRoutesToListBoxLater(ArrayList<UsbFile> routes, int index);
    public abstract void enableRouteButtons(boolean enabled);
}
