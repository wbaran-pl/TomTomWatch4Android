/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package net.studioblueplanet.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;
import java.util.Optional;

import net.studioblueplanet.logger.DebugLogger;

import lombok.Getter;
import lombok.RequiredArgsConstructor;


/**
 * This class represents the connection to the USB watch. It contains functionality
 * to open en close the connection and to exchange request/response by means
 * of blocks of data
 * @author Jorgen
 */
public class UsbConnection
{
    /**
     * Enum defining the device type
     */
    public enum DeviceType 
    {
        DEVICETYPE_UNKNOWN,
        DEVICETYPE_MULTISPORTS,
        DEVICETYPE_SPARK_MUSIC,
        DEVICETYPE_SPARK_CARDIO
    };    

    /**
     * Descriptor for the watch device types
     */
    @Getter
    @RequiredArgsConstructor
    private static class WatchDescriptor {
        private final int writeEndpoint;
        private final int readEndpoint;
        private final int packetSize;
        private final int fileReadChunkSize;
        private final int fileWriteChunkSize;

        public static WatchDescriptor getDescriptor(DeviceType deviceType) {
            switch (deviceType) {
                case DEVICETYPE_MULTISPORTS:
                    return new WatchDescriptor(0x05, 0x84, 64, 50, 54);
                case DEVICETYPE_SPARK_MUSIC:
                case DEVICETYPE_SPARK_CARDIO:
                    return new WatchDescriptor(0x02, 0x81, 256, 242, 246);
                default:
                    throw new IllegalArgumentException("Unknown device type: " + deviceType);
            }
        }
    }

    public static final int                 VARIABLE_LENGTH=-1;
    
    private UsbDevice                       device;
    private UsbInterface                    iface;
    private UsbDeviceConnection             connection;
    private UsbRequest                      outRequest = new UsbRequest();
    private UsbRequest                      inRequest = new UsbRequest();
    private DeviceType                      deviceType;
    private WatchDescriptor                 watchDescriptor;
    private byte                            txMessageCounter;
    private byte                            txPrevMessageCounter;

    @Getter
    private boolean                         isError;
    private String                          lastError;
    
    private byte[]                          rxBuffer;
    private int                             rxLength;
    
    /**
     * The constructor. Initializes the instance.
     */
    public UsbConnection(UsbDevice device, UsbDeviceConnection connection, UsbInterface iface)
    {
        this.device         =device;
        this.iface          =iface;
        this.connection     =connection;
        deviceType          =DeviceType.DEVICETYPE_UNKNOWN;
        txMessageCounter    =0;
        txPrevMessageCounter=-1;
        isError             =false;
        lastError           ="";
    }
    
    /**
     * Try to connect to a TomTom Watch. If successful the variable device
     * will hold the UsbDevice instance, the variable deviceType the type found.
     * If not successful, isError=true and lastError will hold a description.
     */
    public void connect()
    {
        if (device.getVendorId() == (short)0x1390 && device.getProductId() == (short)0x7474) {
            deviceType = DeviceType.DEVICETYPE_MULTISPORTS;
        } else if (device.getVendorId() == (short)0x1390 && device.getProductId() == (short)0x7475) {
            deviceType = DeviceType.DEVICETYPE_SPARK_MUSIC;
        } else if (device.getVendorId() == (short)0x1390 && device.getProductId() == (short)0x7477) {
            deviceType = DeviceType.DEVICETYPE_SPARK_CARDIO;
        } else {
            deviceType = DeviceType.DEVICETYPE_UNKNOWN;
        }
        // claim
        connection.claimInterface(iface, true);

        try {
            watchDescriptor = WatchDescriptor.getDescriptor(deviceType);
            findEndpoint(UsbConstants.USB_DIR_OUT, watchDescriptor.getWriteEndpoint())
                    .ifPresent(ep -> outRequest.initialize(connection, ep));
            findEndpoint(UsbConstants.USB_DIR_IN, watchDescriptor.getReadEndpoint())
                    .ifPresent(ep -> inRequest.initialize(connection, ep));
        } catch (IllegalArgumentException e) {
            isError = true;
            lastError = "USB: Unknown device type";
        }
        if (isError)
        {
            DebugLogger.error(lastError);
        }
    }
    
    /**
     * Disconnects from the TTWatch
     */
    public void disconnect()
    {
        Optional.ofNullable(inRequest).ifPresent(UsbRequest::close);
        Optional.ofNullable(outRequest).ifPresent(UsbRequest::close);
        connection.releaseInterface(iface);
        deviceType  =DeviceType.DEVICETYPE_UNKNOWN;
        device        =null;
    }

    private Optional<UsbEndpoint> findEndpoint(int direction, int address) {
        for (int ii = 0; ii < iface.getEndpointCount();ii++) {
            var ep = iface.getEndpoint(ii);
            if (direction == ep.getDirection() && (address < 0 || address == ep.getAddress())) {
                return Optional.of(ep);
            }
        }
        return Optional.empty();
    }
    
    /* ********************************************************************************************\
    * 
    * USB METHODS - Request/Response
    * 
    \* *******************************************************************************************/
    /**
     * This method executes a number of validity checks on the response
     * @param response Response data
     * @param expectedRxMsg Expected message type
     * @param expectedRxLength Expected message length
     * @return True if valid, false if not
     */
    private boolean validateResponse(byte msg, byte[] response, byte expectedRxMsg, int expectedRxLength)
    {
        boolean valid;
        int     length;
        
        valid=true;
        
        length=response[1]&0xff;
        DebugLogger.debug("Validation: response "+response[0]+
                          " length: "            +length+
                          " msgcount: "          +response[2]+"/"+(this.txPrevMessageCounter)+
                          " type:"               +String.format("0x%02x", response[3])+"/"+String.format("0x%02x", expectedRxMsg));
        
        // Byte 0: must be 0x01
        if (response[0]!=0x01)
        {
            valid  =false;
            isError=true;
            lastError="USB: Invalid response";
        }
        
        // Byte 1: remaining length, is including msgcount and type, hence +2.
        if ((expectedRxLength!=VARIABLE_LENGTH) && (length!=expectedRxLength+2))
        {
            valid  =false;
            isError=true;
            lastError="USB: Invalid response length";
        }
        
        // Byte 2: counter value, duplicate from the value in the request 
        if (response[2]!=this.txPrevMessageCounter)
        {
            valid  =false;
            isError=true;
            lastError="USB: Response not synchonized with the request";
        }
     
        // Byte 3: message type
        if (response[3]!=expectedRxMsg)
        {
            valid  =false;
            isError=true;
            lastError="USB: Wrong response type";                
        }
            
        
        return valid;
    }
    
    
    
    /**
     * This message synchronously sends a requests and waits for the response. The method
     * performs a number of checks.
     * @param msg               The request message type
     * @param txData            The data to send
     * @param rxData            The response data
     * @param expectedRxMsg     Expected response ID, usually equal to the request ID. Exception is msg=MSG_READ_FILE_DATA_REQUEST,
     *                          in this case the response is MSG_READ_FILE_DATA_RESPONSE
     * @param expectedRxLength  Expected response length.
     */
    public void sendRequest(byte msg, UsbPacket txData, UsbPacket rxData, byte expectedRxMsg, int expectedRxLength)
    {
        byte[]          txPacket;
        int             packetSize;
        int             length;

        // Initialize
        isError         =false;

        // Length as integer
        length=(txData.length)&0xff;
        
        // Check if the length of the data in the packet is within limits
        // Packet size is 256, 4 overhead bytes
        if (length>=256-4)
        {
            isError=true;
            lastError="USB Error: tx data to long";
        }
        
        if (!isError)
        {
            if (watchDescriptor == null)
            {
                isError=true;
                lastError="No valid device found";
            }
            else
            {
                packetSize = watchDescriptor.getPacketSize();

                // Construct the overhead bytes
                txPacket            =new byte[packetSize];
                txPacket[0]         =0x09;                          // request
                txPacket[1]         =(byte)(length+2);              // Length+2
                txPacket[2]         =txMessageCounter;              // Message counter
                txPacket[3]         =msg;                           // Request type
                txPrevMessageCounter=txMessageCounter;
                txMessageCounter++;
                
                // Construct the RX buffer size
                rxBuffer            =new byte[packetSize];

                System.arraycopy(txData.data, 0, txPacket, 4, length);
                if (outRequest.queue(ByteBuffer.wrap(txPacket))) {
                    var sent = connection.requestWait();
//                    Log.d("UsbConnection", "sent: " + (sent != null ? sent.getClientData() : "null"));

                    if (inRequest.queue(ByteBuffer.wrap(rxBuffer))) {
                        var rcvd = connection.requestWait();
//                        Log.d("UsbConnection", "rcvd: " + (rcvd != null ? rcvd.getClientData() : "null"));
                    } else {
                        isError = true;
                    }
                } else {
                    isError = true;
                }

                // Validate the response
                if (validateResponse(msg, rxBuffer, expectedRxMsg, expectedRxLength)) {
                    // If valid, copy the response
                    length = (rxBuffer[1] & 0xff) - 2;
                    System.arraycopy(rxBuffer, 4, rxData.data, 0, length);
                    rxData.length = (byte) length;
                }
            }
        }
    }
    
    /**
     * Returns the maximum chunk size when reading files.
     * The chunksize apparently is the buffer size minus 14 bytes.
     * @return The chunk size or -1 if not known
     */
    public int getFileReadChunkSize()
    {
        int chunkSize;
        
        if (watchDescriptor != null)
        {
            chunkSize = watchDescriptor.getFileReadChunkSize();
        }
        else
        {
            chunkSize=-1;
            isError=true;
            lastError="No valid device known";
            DebugLogger.error(lastError);
        }
        return chunkSize;
    }

    /**
     * Returns the maximum chunk size when writing files
     * @return The chunk size or -1 if not known
     */
    public int getFileWriteChunkSize()
    {
        int chunkSize;
        
        if (watchDescriptor != null)
        {
            chunkSize = watchDescriptor.getFileWriteChunkSize();
        }
        else
        {
            chunkSize=-1;
            isError=true;
            lastError="No valid device known";
            DebugLogger.error(lastError);
        }
        return chunkSize;
    }    
    
    
    /**
     * Returns the serial number of the device as string.
     * @return The string or null if an error occurred
     */
    public String getDeviceSerialNumber()
    {
        return device != null ? device.getSerialNumber() : null;
    }

    
}
