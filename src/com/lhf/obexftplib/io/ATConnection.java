/**
 * Last updated in 21/Out/2010
 *
 *    This file is part of JObexFTP.
 *
 *    JObexFTP is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    JObexFTP is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with JObexFTP.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.lhf.obexftplib.io;

import com.lhf.obexftplib.event.ConnectionModeListener;
import com.lhf.obexftplib.event.ATEventListener;
import com.lhf.obexftplib.event.DataEventListener;
import com.lhf.obexftplib.etc.OBEXDevice;
import com.lhf.obexftplib.etc.Utility;
import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.RXTXPort;
//import gnu.io.RXTXPort;
import com.dummy.DummyRXTXPort;
import com.dummy.DummySerialPortEvent;
import com.dummy.DummySerialPortEventListener;

import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import gnu.io.UnsupportedCommOperationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.TooManyListenersException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class used for Serialconnections using AT protocol
 * @author Ricardo Guilherme Schmidt <ricardo@lhf.ind.br>
 */
public class ATConnection {

    /**
     * Defines the Flow Control to none;
     * @see setFlowControl()
     */
    public static final byte FLOW_NONE = '0';
    /**
     * Defines the Flow Control to none;
     * @see setFlowControl()
     */
    public static final byte FLOW_XONXOFF = '1';
    /**
     * Defines the Flow Control to none;
     * @see setFlowControl()
     */
    public static final byte FLOW_RTSCTS = '3';
    /**
     * Defines the Connection Mode to disconnected;
     * @see setConnMode(int newConnMode)
     */
    public static final int MODE_DISCONNECTED = 0;
    /**
     * Defines the Connection Mode to AT Mode;
     * @see setConnMode(int newConnMode)
     */
    public static final int MODE_AT = 1;
    /**
     * Defines the Connection Mode to Data Mode;
     * @see setConnMode(int newConnMode)
     */
    public static final int MODE_DATA = 2;
    private static final Logger LOGGER = Utility.getLogger();
    private final DummySerialPortEventListener eventListener = new ATSerialPortEventListener();
    private final ArrayList<ConnectionModeListener> connModeListners = new ArrayList<ConnectionModeListener>(10);
    private final ArrayList<DataEventListener> dataEventListners = new ArrayList<DataEventListener>(10);
    private final ArrayList<ATEventListener> atEventListners = new ArrayList<ATEventListener>(10);
    private final Object holder = new Object();
    private int connMode = MODE_DISCONNECTED;
    private int baudRate = 460800;
    private byte flowControl = FLOW_RTSCTS;
    private byte[] incomingData;
    private boolean hasIncomingPacket;
    private CommPortIdentifier commPortIdentifier;
    private InputStream is;
    private OutputStream os;
    private DummyRXTXPort serialPort;
    private OBEXDevice device;
    private int errors = 0;

    /**
     * Creates a connection stream to serial device using a string as port identifier
     * @param connPortPath the path to commport
     * @throws NoSuchPortException if the path is invalid
     * @throws PortInUseException if the port is in use
     */
    public ATConnection(final String connPortPath) throws NoSuchPortException, PortInUseException {
        this(Utility.addPortToRxTx(connPortPath));
    }

    /**
     * Creates a connection stream to serial device using a CommPortIdentifier
     * @param commPortIdentifier
     */
    public ATConnection(final CommPortIdentifier commPortIdentifier) {
        this.commPortIdentifier = commPortIdentifier;
    }

    /**
     * Defines the connection mode.
     * @param newConnMode the constant id to connection mode to be set
     * @throws IOException if a io error occurs
     * @throws UnsupportedCommOperationException
     * @throws PortInUseException
     * @see ATConnection#MODE_DISCONNECTED
     * @see ATConnection#MODE_AT
     * @see ATConnection#MODE_DATA
     */
    public synchronized void setConnMode(final int newConnMode) throws IOException {
        if (connMode == newConnMode) {
            return;
        }
        LOGGER.log(Level.FINEST, "Switching from connection mode {0} to mode {1}.", new String[]{Integer.toString(connMode), Integer.toString(newConnMode)});

        notifyModeListeners(newConnMode, false);
        switch (connMode) {
            case MODE_DISCONNECTED:
                try {
                    open();
                } catch (UnsupportedCommOperationException ex) {
                    terminate();
                    throw new IOException("Operação não suportada: " + ex.getMessage(), ex);
                } catch (PortInUseException ex) {
                    terminate();
                    throw new IOException("A porta " + commPortIdentifier.getName() + " está em uso", ex);
                }
                if (newConnMode == MODE_DATA) {
                    connMode = openDataMode() ? MODE_DATA : connMode;
                }
                break;
            case MODE_AT:
                if (newConnMode == MODE_DISCONNECTED) {
                    try {
                        close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        connMode = MODE_DISCONNECTED;
                    }
                } else {
                    connMode = openDataMode() ? MODE_DATA : connMode;
                }
                break;
            case MODE_DATA:
                connMode = closeDataMode() ? MODE_AT : connMode;
                if (newConnMode == MODE_DISCONNECTED) {
                    close();
                    connMode = MODE_DISCONNECTED;
                }
                break;
        }
        if (connMode == newConnMode) {
            errors = 0;
            notifyModeListeners(connMode, true);
        } else {
            errors++;
            if (errors > 5) {
                terminate();
                throw new IOException("I/O Error. Cannot communicate properly.");
            }
        }
    }

    /**
     * Sends (AT) commands to the stream.
     * @param s the string to be send
     * @return the response
     * @throws IOException if OutputStream or InputStream gives error, is closed, or if connMode is wrong.
     */
    public synchronized byte[] send(final byte[] b, final int timeout) throws IOException {
        if (connMode != MODE_AT) {
            LOGGER.log(Level.WARNING, "Trying to send in wrong mode. Mode is {0}", connMode);
        }
        return sendPacket(b, timeout);
    }

    /**
     * Method used to auto identify the device, if it is not yet identified.
     */
    public void identifyDevice() throws IOException {
        if (device == null && connMode == MODE_AT) {
            String s = new String(send("AT+CGMM\r".getBytes(), 500));
            if (s.indexOf("ERROR") > -1) {
                LOGGER.log(Level.WARNING, "Warning: Device is in wrong mode.");
            } else if (s.indexOf("TC65") > -1) {
                LOGGER.log(Level.FINE, "Found TC65 device.");
                setDevice(OBEXDevice.TC65);
            } else {
                LOGGER.log(Level.WARNING, "Unknown device" + s + ", using default settings");
                setDevice(OBEXDevice.DEFAULT);
            }
        }

    }

    /**
     * Method used to auto identify the device, if it is not yet identified.
     */
    public boolean identifyDevice(byte[] b) throws IOException {
        if (device == null && connMode == MODE_AT) {
            String s = new String(b);
            if (s.indexOf("ERROR") > -1) {
                LOGGER.log(Level.WARNING, "Warning: Device is in wrong mode.");
            } else if (s.indexOf("TC65") > -1) {
                LOGGER.log(Level.FINE, "Found TC65 device.");
                setDevice(OBEXDevice.TC65);
                return true;
            } else {
                LOGGER.log(Level.WARNING, "Unknown device" + s + ", using default settings");
                setDevice(OBEXDevice.DEFAULT);
            }
        }
        return false;
    }

    public boolean isAnswering() {
        try {
            if (send(getDevice().CMD_CHECK, 200).length > 0) {
                return true;
            }
        } catch (IOException ex) {
            Logger.getLogger(ATConnection.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }

    /**
     * Adds a ConnectionModeListener to recieve connection mode changes notifications.
     * @param listener the listener.
     * @see ConnectionModeListener
     */
    public void addConnectionModeListener(final ConnectionModeListener listener) {
        connModeListners.add(listener);
    }

    /**
     * Removes a ConnectionModeListener to recieve connection mode changes notifications.
     * @param listener the listener.
     * @see ConnectionModeListener
     */
    public void removeConnectionModeListener(final ConnectionModeListener listener) {
        connModeListners.remove(listener);
    }

    /**
     * Adds a ConnectionModeListener to recieve ready incoming data
     * @param listener the listener.
     * @see DataEventListener
     */
    public void addDataEventListener(final DataEventListener listener) {
        dataEventListners.add(listener);
    }

    /**
     * Adds a ATEventListener to recieve ready incoming data
     * @param listener the listener.
     * @see ATEventListener
     */
    public void addATEventListener(final ATEventListener listener) {
        atEventListners.add(listener);
    }

    /**
     * Removes a DataEventListener to recieve ready incoming data
     * @param listener the listener.
     * @see DataEventListener
     */
    public void removeDataEventListener(final DataEventListener listener) {
        dataEventListners.remove(listener);
    }

    /**
     * Removes a ATEventListener to recieve ready incoming data
     * @param listener the listener.
     * @see ATEventListener
     */
    public void removeATEventListener(final ATEventListener listener) {
        atEventListners.remove(listener);
    }

    /**
     * Sends packet data to output stream
     * @param b the data
     * @param timeout timeout of waiting
     * @return an array containging the data, or an array of 0 positions if timedout
     * @throws IOException if an IO exceptio occurs
     */
    private synchronized byte[] sendPacket(final byte[] b, final int timeout) throws IOException {
        LOGGER.log(Level.FINER, "Send {0}", new String(b));
        synchronized (holder) {
            os.write(b);
            try {
                hasIncomingPacket = false;
                holder.wait(timeout);
            } catch (InterruptedException iE) {
            }
        }
        if (!hasIncomingPacket) {
            incomingData = new byte[0];
        }
        LOGGER.log(Level.FINER, "Recieve {0}", new String(incomingData));
        return incomingData;
    }

    /**
     * Open SerialPort, set the serialport params defined, the flowcontrol defined and the inputstream and the outputstream;
     * @throws IOException if an io error occurs
     * @throws UnsupportedCommOperationException if it could not set the serialport params or the flowcontrol
     * @throws PortInUseException if the port specified is in use.
     */
    private synchronized void open() throws IOException, UnsupportedCommOperationException, PortInUseException {
        LOGGER.log(Level.FINEST, "Configuring serial port");
        //CommPort commPort = commPortIdentifier.open(this.getClass().getName(), 2000);
        //serialPort = (RXTXPort) commPort;
        serialPort = new DummyRXTXPort(commPortIdentifier.getName());
        serialPort.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        serialPort.setEndOfInputChar((byte) 10);
        switch (flowControl) {
            case FLOW_NONE:
                serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
                break;
            case FLOW_XONXOFF:
                serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_XONXOFF_IN | SerialPort.FLOWCONTROL_XONXOFF_OUT);
                break;
            case FLOW_RTSCTS:
                serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_OUT | SerialPort.FLOWCONTROL_RTSCTS_IN);
                break;
        }
        LOGGER.log(Level.FINEST, "Opening I/O");
        is = serialPort.getInputStream();
        os = serialPort.getOutputStream();
        serialPort.enableReceiveTimeout(1000);
        serialPort.setOutputBufferSize(512);
        serialPort.setInputBufferSize(512);
        serialPort.notifyOnDataAvailable(true);
        try {
            serialPort.addEventListener(eventListener);
        } catch (TooManyListenersException ex) {
            Logger.getLogger(ATConnection.class.getName()).log(Level.SEVERE, null, ex);
        }
        connMode = MODE_AT;
        onOpen();

    }

    /**
     * Method called after a connection is opened.
     */
    protected void onOpen() throws IOException {
        estabilize();
        identifyDevice();
    }

    /**
     * Estabilizates the input/output operations, and turns off echo mode.
     * @throws IOException
     */
    public void estabilize() throws IOException {
        LOGGER.log(Level.FINEST, "Estabilizating I/O");
//        sendPacket(OBEXDevice.CMD_CHECK, 50);
        if (send(OBEXDevice.CMD_CHECK, 50).length < 1) {
            closeDataMode();
        }
        send(new byte[]{'A', 'T', 'E', '\r'}, 50);
        send(new byte[]{'A', 'T', 'E', '\r'}, 50);
        send(OBEXDevice.CMD_CHECK, 50);
        send(OBEXDevice.CMD_CHECK, 50);
    }

    /**
     * Method called before the comm port and it streams are closed.
     */
    protected void onClose() throws IOException {
        sendPacket(new byte[]{'A', 'T', 'Z', '\r'}, 50);
    }

    /**
     * Closes the SerialPort, the OutputStream and the InputStream, consecutevly.
     * @throws IOException if an IO error occurs.
     */
    private void close() throws IOException {
        try {
            onClose();
        } finally {
            terminate();
        }
    }

    public void terminate() {
        System.out.println("Terminating...");
        if (serialPort != null) {
            serialPort.removeEventListener();
        }
        if (os != null) {
            try {
                os.close();
            } catch (IOException ex) {
                Logger.getLogger(ATConnection.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (is != null) {
            try {
                is.close();
            } catch (IOException ex) {
                Logger.getLogger(ATConnection.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (serialPort != null) {
            serialPort.close();
        }

    }

    /**
     * Trys at least 5 times closing the datamode by sending +++ bytes to serialport.+++++++++
     * @return true if datamode is closed and it sent 10 79 75 13 confirmation bytes, false otherwise
     * @throws IOException if an IO error occurs.
     */
    private boolean closeDataMode() throws IOException {
        LOGGER.log(Level.FINEST, "Closing datamode.");
        boolean b = false;
        for (int i = 0; (b = sendPacket(new byte[]{'+', '+', '+'}, 1000).length < 1) && i < 5; i++);
        connMode = (Utility.arrayContainsOK(sendPacket(getDevice().CMD_CHECK, 50)) ? MODE_DATA : MODE_AT);
        return !b;
    }

    /**
     * This method sets the flowcontrol in atcmd and opiens a data connection++++++
     * @return true if datamode is stablished
     * @throws IOException if an IO Error occurs.
     */
    private boolean openDataMode() throws IOException {
        LOGGER.log(Level.FINEST, "Opening datamode.");
        send(getDevice().getFlowControl(flowControl), 500);
        if (!Utility.arrayContainsOK(send(getDevice().getObexCheck(), 500))) {
            connMode = MODE_AT;
            return false;
        }
        boolean b = Utility.arrayContainsOK(send(getDevice().getObexOpen(), 2000));
        connMode = (b ? MODE_DATA : MODE_AT);
        return b;
    }

    /**
     * Method used for notifying the listeners when a connectionmode has changed.
     */
    private void notifyModeListeners(final int newConnMode, boolean changed) {
        for (int i = 0; i < connModeListners.size(); i++) {
            connModeListners.get(i).update(newConnMode, changed);
        }
    }

    /**
     * Method used for notifying the dataeventlisteners when a incoming data is ready.
     */
    private void notifyDataEventListeners(final byte[] event) {
        for (Iterator<DataEventListener> it = dataEventListners.iterator(); it.hasNext();) {
            it.next().DataEvent(event);
        }
    }

    /**
     * Method used for notifying the ateventlisteners when a incoming at is ready.
     */
    protected void notifyATEventListeners(final byte[] event) {
        for (Iterator<ATEventListener> it = atEventListners.iterator(); it.hasNext();) {
            it.next().ATEvent(event);
        }
    }

    /***********************
     * Getters and Setters *
     ***********************/
    /**
     * Gets the inuse inputstream
     * @return the InputStream of serialPort created by connection
     */
    InputStream getInputStream() {
        return is;
    }

    /**
     * Gets the used outputsteram.
     * @return the OutputStream of serialPort created by connection
     */
    OutputStream getOutputStream() {
        return os;
    }

    /**
     * Gets the CommPortIdentifier used by connection.
     * @return the CommPortIdentifier used by connection.
     */
    public CommPortIdentifier getCommPortIdentifier() {
        return commPortIdentifier;
    }

    /**
     * Get the current connection mode
     * @return the connection mode.
     * @see ATConnection#MODE_DISCONNECTED
     * @see ATConnection#MODE_AT
     * @see ATConnection#MODE_DATA
     */
    public int getConnMode() {
        return connMode;
    }

    /**
     * Sets the baudrate.
     * This method just takes effect before connect, if it is setted in a connected state, there is a need to disconnect and reconnect to take effect.
     * @param baudRate
     */
    public void setBaudRate(final int baudRate) {
        this.baudRate = baudRate;
    }

    /**
     * Sets the flow control
     * This method just takes effect before connect, if it is setted in a connected state, there is a need to disconnect and reconnect to take effect.
     * @param flowControl the flow control to set
     * @see ATConnection#FLOW_NONE
     * @see ATConnection#FLOW_RTSCTS
     * @see ATConnection#FLOW_XONXOFF
     */
    public void setFlowControl(final byte flowControl) {
        if (flowControl != FLOW_NONE && flowControl != FLOW_RTSCTS && flowControl != FLOW_XONXOFF) {
            throw new IllegalArgumentException("Unknown flowcontrol type");
        }
        this.flowControl = flowControl;

    }

    /**
     * Method that gets the flowcontrol setted
     * @return the setted flowcontrol
     * @see ATConnection#FLOW_NONE
     * @see ATConnection#FLOW_RTSCTS
     * @see ATConnection#FLOW_XONXOFF
     */
    public byte getFlowControl() {
        return flowControl;
    }

    /**
     * @return the device
     */
    public OBEXDevice getDevice() {
        return device;
    }

    /**
     * @param device the device to set
     * @see OBEXDevice#values()
     */
    public void setDevice(final OBEXDevice device) {
        this.device = device;
    }

    public byte[] readAll() throws IOException {
        byte[] incomingData = new byte[is.available()];
        is.read(incomingData);
        return incomingData;
    }

    /**
     * Private class to hold the SerialPortEventListener#serialEvent(gnu.io.SerialPortEvent) out visibility of public.
     * @see SerialPortEventListener#serialEvent(gnu.io.SerialPortEvent)
     */
    private final class ATSerialPortEventListener implements DummySerialPortEventListener {

        /**
         * Method used to recieve SerialPortEvents.
         * @param spe
         */
        public void serialEvent(final DummySerialPortEvent spe) {
            synchronized (holder) {
                switch (spe.getEventType()) {
                    case SerialPortEvent.DATA_AVAILABLE:
                        try {
                            incomingData = readAll();
                        } catch (Throwable ex) {
                            notifyModeListeners(connMode, false);
                            terminate();
                            connMode = MODE_DISCONNECTED;
                            notifyModeListeners(connMode, true);
                        }
                        hasIncomingPacket = true;
                        holder.notifyAll();
                        if (connMode == MODE_DATA) {
                            notifyDataEventListeners(incomingData);
                        } else if (connMode == MODE_AT) {
                            notifyATEventListeners(incomingData);
                        }
                }
            }
        }
    }
}
