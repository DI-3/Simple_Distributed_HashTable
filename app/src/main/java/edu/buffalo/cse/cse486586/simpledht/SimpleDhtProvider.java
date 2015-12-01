package edu.buffalo.cse.cse486586.simpledht;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Formatter;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

public class SimpleDhtProvider extends ContentProvider {


    static final String TAG = SimpleDhtProvider.class.getSimpleName();
    static final int SERVER_PORT = 10000;
    private static final String KEY_FIELD = "key";
    private static final String VALUE_FIELD = "value";
    static final String REMOTE_PORT0 = "11108";
    public static String homePort = null;
    private final String JOIN_IND = "JOIN";
    private final String DELIMITER = "###";
    public static ServerSocket servSocket = null;
    public static ChordTable myChordTable = null;
    public static boolean isDatavbl = false;
    public static String searchData = null;
    public static String dhtDatas = null;
    public static boolean isDatavblDHT = false;

    public class ChordTable {

        public String port;
        public String hashVal;
        public String succ = null;
        public String pred = null;

        public ChordTable(String gPort, String gHashVal, String gsucc, String gpred) {
            this.port = gPort;
            this.hashVal = gHashVal;
            ;
            this.succ = gsucc;
            this.pred = gpred;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        if (selection.equals("\"*\"")) {
            deleteAllFiles();
            if (myChordTable.succ != null && myChordTable.pred != null)
                sendToSuccessorNode(null, "SEND", "DELALL");

        } else if (selection.equals("\"@\"")) {
            deleteAllFiles();
        } else {
            if (validate(selection)) {
                deleteFile(selection);
                Log.i("DEL_SERV", "Delete file" + selection);
            } else {

                StringBuilder sendDelReq = new StringBuilder();
                sendDelReq.append("DEL").append(DELIMITER).append(selection);
                Log.i("DEL_SERV", "Delete req: " + sendDelReq.toString());
                sendToSuccessorNode(null, "SEND", sendDelReq.toString());
            }
        }
        return 0;
    }

    public void deleteFile(String filename) {
        Log.i("FILE_SERV", "Deleting file....." + filename);
        getContext().deleteFile(filename);
    }

    public void deleteAllFiles() {

        for (String filename : getContext().fileList())
            deleteFile(filename);

    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }


    public boolean validate(String key) {

        boolean retVal = false;
        if (myChordTable.pred == null && myChordTable.succ == null)
            retVal = true;
        else {
            retVal = chordAlg(key);
        }
        return retVal;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO Auto-generated method stub

        Log.i("INSERTPATH", "Path of filr" + uri.getPath().toString());

        String filename = values.getAsString("key");
        String value = values.getAsString("value");

        try {
            if (validate(filename)) {
                FileOutputStream fpOutput = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                fpOutput.write(value.getBytes());
                fpOutput.close();
            } else {
                StringBuilder d = new StringBuilder();
                d.append("INSERT").append(DELIMITER).append(homePort).append(DELIMITER).append(filename).append(DELIMITER).append(value);
                Log.i("INSERT_SUCC", "Sending to Succ" + myChordTable.succ);
                sendToSuccessorNode(myChordTable.succ, "SEND", d.toString());
            }
        } catch (IOException ae) {
            Log.e(TAG, "Filewrite  error - IOException");
        }

        Log.v("insert", values.toString());
        return uri;

    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub

        TelephonyManager telpmngr1 = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = telpmngr1.getLine1Number().substring(telpmngr1.getLine1Number().length() - 4);
        final String myServerPort = String.valueOf(Integer.parseInt(portStr) * 2);
        homePort = myServerPort;

        try {
            servSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, servSocket);

        } catch (IOException ae) {
            try {
                servSocket.close();
            } catch (Exception we) {

            }
            Log.e(TAG, "Error opening sever socket");
            System.out.println();
            ae.printStackTrace();
        }
        String hashVal = null;
        int emulatorv = Integer.valueOf(myServerPort) / 2;

        try {
            hashVal = genHash(String.valueOf(emulatorv));
        } catch (Exception ae) {
            Log.i("EXCEPTION", "Excelption in HasValFunction");
        }
        myChordTable = new ChordTable(myServerPort, hashVal, null, null);
        String strMsg = "test";

        new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, strMsg, homePort);

        return false;
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];

            try {
                /* the server keeps on listening on the server socket */
                while (true) {

                    Socket socket = serverSocket.accept();
                    //Reference :http://docs.oracle.com/javase/tutorial/networking/sockets/readingWriting.html
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String msg = in.readLine();

                    processData(msg);
                    in.close();
                    socket.close();
                }
            } catch (IOException ex) {
                Log.e(TAG, "ClientTask socket IOException");


            }
            return null;
        }

        /* This method checks for the msg and based on the protocol message type
           inserts the data or forwards to the next node
          */
        public void processData(String msg) {

            String[] data = msg.split("###");
            // Checking the message type and deciding the flow
            if (data[0].equals("JOIN")) {
                joinNode(data);
            } else if (data[0].equals("SUCC")) {
                myChordTable.succ = data[1];
                Log.i("CHORD-TABLE :", myChordTable.port + "-PRED :" + myChordTable.pred + "-SUCC :" + myChordTable.succ);

            } else if (data[0].equals("UPDSUCCPRED")) {

                myChordTable.succ = data[1];
                myChordTable.pred = data[2];
                Log.i("CHORD-TABLE :", myChordTable.port + "-PRED :" + myChordTable.pred + "-SUCC :" + myChordTable.succ);
            } else if (data[0].equals("INSERT")) {
                //Validate if the data / key is to be stored in this Node or Sent to succuessor
                if (validate(data[2])) {
                    try {
                        Log.i("INSERT_FILE", msg);
                        FileOutputStream fpOutput = getContext().openFileOutput(data[2], Context.MODE_PRIVATE);
                        fpOutput.write(data[3].getBytes());
                        fpOutput.close();
                    } catch (IOException ae) {
                        System.out.println("File IO Exception");
                    }
                } else {
                    sendToSuccessorNode(myChordTable.succ, "SEND", msg);
                }

            } else if (data[0].equals("SEARCH")) {
                Log.i("FOUND", "searchReacjed" + homePort);
                if (validate(data[2])) {
                    String portNum = data[1];
                    Log.i("FOUND", "The data found:" + homePort);
                    //Fetch and send to
                    try {
                        int emulator = Integer.valueOf(portNum) / 2;
                        StringBuilder sb = new StringBuilder();
                        FileInputStream fpInput = getContext().openFileInput(data[2]);
                        BufferedReader br = new BufferedReader(new InputStreamReader(fpInput));
                        String dat = br.readLine();
                        sb.append("FILEFOUND").append(DELIMITER).append(data[2]).append(DELIMITER).append(dat);
                        send(String.valueOf(emulator), sb.toString());
                    } catch (IOException ae) {

                        Log.i("EXCEPTION", "Exception file found");
                    }


                } else {
                    sendToSuccessorNode(myChordTable.succ, "SEND", msg);
                }
            } else if (data[0].equals("FILEFOUND")) {

                Log.i("FILE_FOUND", "filereached");
                synchronized ((Boolean) isDatavbl) {
                    isDatavbl = true;
                }
                searchData = msg;
                Log.i("FILE_FOUND", msg);
            } else if (data[0].equals("GETDHT")) {

                if (data[1].equals(homePort)) {
                    Log.i("DHT-C", "beforecheck" + homePort);
                    if (data.length > 2)
                        dhtDatas = data[2];
                    Log.i("DHT-C", "checkt the data" + dhtDatas);
                    synchronized ((Boolean) isDatavbl) {
                        isDatavblDHT = true;
                    }
                    Log.i("ALLDHT", "DHT -" + data[2]);


                } else {

                    Log.i("DHT", "DHT-" + msg);
                    StringBuilder sbDHT = new StringBuilder();
                    sbDHT.append(msg).append(getAllFiles());
                    Log.i("DHT", "DHT -" + sbDHT.toString());
                    sendToSuccessorNode(null, "SEND", sbDHT.toString());
                }

            } else if (data[0].equals("DEL")) {

                if (validate(data[1])) {
                    Log.i("FILE_DEL", "Deleting file :" + data[1]);
                    deleteFile(data[1]);

                } else {
                    Log.i("FILE_DEL", "Sedning the file del req to:" + myChordTable.succ);
                }
            } else if (data[0].equals("DELALL")) {
                deleteAllFiles();
                sendToSuccessorNode(null, "SEND", "DELALL");
            }

        }

        public void joinNode(String[] data) {

            /*Todo Update the cenralized hash table */
            int emulatorv = Integer.valueOf(data[1]) / 2;
            if (emulatorv != Integer.valueOf(homePort) / 2) {
                try {

                    if (myChordTable.pred == null && myChordTable.succ == null) {

                        myChordTable.succ = String.valueOf(emulatorv);
                        myChordTable.pred = String.valueOf(emulatorv);
                        //sendToSuccessorNode(String.valueOf(emulatorv),"JOIN");
                        int emulatorS = Integer.parseInt(homePort) / 2;
                        String strEmulator = String.valueOf(emulatorS);
                        sendToNode(strEmulator, strEmulator, String.valueOf(emulatorv));
                    } else {

                        if (chordAlg(String.valueOf(emulatorv))) {

                            //Send Message to Predecessor to set this node as the successor
                            sendToPredecessorNode(String.valueOf(emulatorv), "SUCC");
                            int emulatorS = Integer.parseInt(homePort) / 2;
                            String strEmulator = String.valueOf(emulatorS);
                            sendToNode(strEmulator, myChordTable.pred, String.valueOf(emulatorv));
                            myChordTable.pred = String.valueOf(emulatorv);
                            Log.i("CHORD-TABLE :", myChordTable.port + "-PRED :" + myChordTable.pred + "-SUCC :" + myChordTable.succ);

                        } else {
                            // send to Successor
                            sendToSuccessorNode(String.valueOf(emulatorv), "JOIN", null);
                            Log.i("SEND_TO_SUCC", "Sending to the successor");
                        }

                    }

                } catch (Exception ae) {
                    Log.i("EXCEPTION", "Excelption in HasValFunction");
                }

            }
            System.out.println("Checkpoint1data from" + data[1]);
            Log.i("JOIN", data[1]);
        }

        public void sendToNode(String succEmul, String predEmul, String sendToEmulator) {

            StringBuilder sb = new StringBuilder();
            sb.append("UPDSUCCPRED").append(DELIMITER).append(succEmul).append(DELIMITER).append(predEmul);
            send(sendToEmulator, sb.toString());

        }

        protected void onProgressUpdate(String... strings) {
            /*
             * The following code displays what is received in doInBackground().
             */
            String strReceived = strings[0].trim();
            String filename = "GroupMessengerOutput";
            String string = strReceived + "\n";
            FileOutputStream outputStream;

            try {
                outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
                outputStream.write(string.getBytes());
                outputStream.close();
            } catch (Exception e) {
                Log.e(TAG, "File write failed");
            }

            return;
        }
    }

    public void send(String emulator, String strMsg) {

        Socket socket = null;
        int portNo = Integer.parseInt(emulator) * 2;
        String sendPortNO = String.valueOf(portNo);

        try {

            socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    Integer.parseInt(sendPortNO));

            //Reference :http://docs.oracle.com/javase/tutorial/networking/sockets/readingWriting.html
            DataOutputStream out_stream = new DataOutputStream(socket.getOutputStream());
            OutputStream opStream = socket.getOutputStream();
            out_stream.writeBytes(strMsg);
            Log.i("SEND_TO_SUCC", sendPortNO + " : " + strMsg);
            out_stream.flush();
            out_stream.close();
            opStream.close();
            socket.close();

        } catch (UnknownHostException e) {
            Log.e(TAG, "ClientTask UnknownHostException");
        } catch (IOException e) {
            //servSocket.close();
            try {
                socket.close();
                servSocket.close();
            } catch (Exception io) {
                System.out.println("The socket exception");
            }

            Log.e(TAG, "ClientTask socket IOException");
            System.out.println();
            e.printStackTrace();

        }

    }

    public void sendToSuccessorNode(String emulatorV, String option, String dataToSend) {

        String strPort = null;
        if (emulatorV != null) {
            int portNum = Integer.parseInt(emulatorV) * 2;
            strPort = String.valueOf(portNum);

        }
        if (option.equals("JOIN")) {
            StringBuilder sbJoin = new StringBuilder();
            sbJoin.append(JOIN_IND).append(DELIMITER).append(strPort);
            //int succPortNum = Integer.parseInt(myChordTable.succ)*2 ;
            send(myChordTable.succ, sbJoin.toString());
        } else if (option.equals("SEND")) {   // send to the successor
            Log.i("INSERT_SUCC", dataToSend);
            send(myChordTable.succ, dataToSend);
        }
    }


    public void sendToPredecessorNode(String emulatorV, String option) {

        int portNum = Integer.parseInt(emulatorV) * 2;
        String strPort = String.valueOf(portNum);
        // To set the Predecessor Nodes Successor the current Node
        if (option.equals("SUCC")) {
            StringBuilder sbSucc = new StringBuilder();
            sbSucc.append("SUCC").append(DELIMITER).append(emulatorV);
            int predPortNum = Integer.parseInt(myChordTable.pred) * 2;
            send(myChordTable.pred, sbSucc.toString());
        }
    }

    /* This method implements the ring routing algorithm of Chord */

    public boolean chordAlg(String key) {
        boolean retVal = false;
        try {
            String hashVal = genHash(key);

            if (genHash(myChordTable.pred).compareTo(myChordTable.hashVal) > 0) {

                if (hashVal.compareTo(genHash(myChordTable.pred)) > 0 || hashVal.compareTo(myChordTable.hashVal) <= 0) {
                    retVal = true;
                } else {
                    retVal = false;
                }

            } else {

                if (hashVal.compareTo(genHash(myChordTable.pred)) > 0 && hashVal.compareTo(myChordTable.hashVal) <= 0) {
                    retVal = true;
                } else {
                    retVal = false;
                }
            }

        } catch (NoSuchAlgorithmException ns) {
            Log.e("Exception", "Exception generating the hashvalue");
        }

        return retVal;
    }

    /***
     * Client Task extends Aynctask and its duty is to multicast the message to all the nodes in the network
     */
    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {

            String centralNodeport = REMOTE_PORT0;
            Socket socket = null;
            if (!homePort.equals(REMOTE_PORT0)) {
                try {

                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(centralNodeport));

                    StringBuilder sbJoin = new StringBuilder();
                    sbJoin.append(JOIN_IND).append(DELIMITER).append(homePort);
                    //Reference :http://docs.oracle.com/javase/tutorial/networking/sockets/readingWriting.html
                    DataOutputStream out_stream = new DataOutputStream(socket.getOutputStream());
                    OutputStream opStream = socket.getOutputStream();
                    out_stream.writeBytes(sbJoin.toString());
                    Log.i("SENDJOIN", homePort);
                    out_stream.flush();
                    out_stream.close();
                    opStream.close();
                    socket.close();

                } catch (UnknownHostException e) {
                    Log.e(TAG, "ClientTask UnknownHostException");
                } catch (IOException e) {
                    //servSocket.close();
                    try {
                        socket.close();
                        servSocket.close();
                    } catch (Exception io) {
                        System.out.println("The socket exception");
                    }
                    Log.e(TAG, "ClientTask socket IOException");
                    System.out.println();
                    e.printStackTrace();
                }
            }
            return null;
        }
    }


    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {

        String filename1 = selection;
        String[] coulmnNames1 = {"key", "value"};

        MatrixCursor a = new MatrixCursor(coulmnNames1);
        Log.i("PATH", "PAthoffile" + uri.getPath().toString());
        if (selection.equals("\"*\"")) {

            a = fetchAllFiles();
            if (myChordTable.pred != null && myChordTable.succ != null)
                a = fetchFromAllDHTS(a);

        } else if (selection.equals("\"@\"")) {
            a = fetchAllFiles();
        } else {

            StringBuilder sbSearch = new StringBuilder();
            if (validate(filename1)) {
                a = fetchKey(filename1);
            } else {
                sbSearch.append("SEARCH").append(DELIMITER).append(homePort).append(DELIMITER).append(filename1);
                new ClientSearch().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, sbSearch.toString());
                while (true) {
                    if (!isDatavbl) {

                    } else {
                        break;
                    }
                }
                isDatavbl = false;
                if (searchData != null) {
                    String[] nodeData = searchData.split(DELIMITER);
                    Log.i("SEARCH", "Data reached");
                    Object[] colVals = {nodeData[1], nodeData[2]};
                    a.addRow(colVals);
                }
            }

        }
        Log.v("query", selection);
        return a;
    }

    public MatrixCursor fetchFromAllDHTS(MatrixCursor a) {

        StringBuilder sbDHT = new StringBuilder();
        sbDHT.append("GETDHT").append(DELIMITER).append(homePort).append(DELIMITER);
        new ClientSearch().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, sbDHT.toString());

        while (true) {
            if (!isDatavblDHT) {
            } else {
                break;
            }
        }
        isDatavblDHT = false;
        Log.i("ROW", "totaldata" + dhtDatas);

        if (dhtDatas != null) {
            String[] nodeData = dhtDatas.split("\\$\\$\\$");
            Log.i("ROW", "RowDatasno" + nodeData[nodeData.length - 1]);
            for (int i = 0; i < nodeData.length; i++) {
                Log.i("ROW", nodeData[i]);
                String[] cols = nodeData[i].split("@@");
                a.addRow(new String[]{cols[0], cols[1]});
            }
        }
        return a;
    }

    public MatrixCursor fetchKey(String filename1) {

        String[] columnNames = {"key", "value"};
        MatrixCursor ar = new MatrixCursor(columnNames);

        try {
            // Reference http://www.mkyong.com/java/how-to-convert-inputstream-to-string-in-java/
            byte[] buffer = new byte[300];
            StringBuilder sb = new StringBuilder();
            FileInputStream fpInput = getContext().openFileInput(filename1);
            BufferedReader br = new BufferedReader(new InputStreamReader(fpInput));
            sb.append(br.readLine());
            Object[] colVals = {filename1, sb.toString()};
            ar.addRow(colVals);
            fpInput.close();
        } catch (IOException e) {
            Log.i("FILE_DEL", "File Not Found" + filename1);
        }
        return ar;
    }

    public MatrixCursor fetchAllFiles() {

        String[] coulmnNames = {"key", "value"};
        MatrixCursor ax = new MatrixCursor(coulmnNames);

        FileInputStream fpInput;
        for (String filename : getContext().fileList()) {
            try {
                //Loop through all the files --
                //StringBuilder sb = new StringBuilder();
                fpInput = getContext().openFileInput(filename);
                BufferedReader br = new BufferedReader(new InputStreamReader(fpInput));
                //int readSize = fpInput.read(buffer);
                String line;
                line = br.readLine();
                //sb.append(br.readLine());
                //Object[] colVals = {filename, sb.toString()};
                Log.i("FILES: ", filename + " ValueS:" + line);
                ax.addRow(new String[]{filename, line});
                fpInput.close();
            } catch (Exception ae) {
                System.out.println("Error Popping");
            }
        }
        return ax;
    }

    /* Fetches all the keys stored in the files */
    public String getAllFiles() {

        FileInputStream fpInput;
        StringBuilder sb = new StringBuilder();
        for (String filename : getContext().fileList()) {
            try {

                fpInput = getContext().openFileInput(filename);
                BufferedReader br = new BufferedReader(new InputStreamReader(fpInput));
                String line;
                line = br.readLine();
                sb.append(filename).append("@@").append(line);
                fpInput.close();
            } catch (Exception ae) {
                System.out.println("Error Popping");
            }
            sb.append("$$$");
        }

        return sb.toString();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    /* Function generates the Hash of the Key using SHA-1 */

    public String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }


    /* The client task  that waits for the response. */
    private class ClientSearch extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... msgs) {

            Socket socket = null;
            String data = msgs[0];
            String[] dataArray = data.split(DELIMITER);

            int emulator = Integer.parseInt(myChordTable.succ);
            int portNum = emulator * 2;
            //sNo++;
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(String.valueOf(portNum)));

                //Reference :http://docs.oracle.com/javase/tutorial/networking/sockets/readingWriting.html
                DataOutputStream out_stream = new DataOutputStream(socket.getOutputStream());
                OutputStream opStream = socket.getOutputStream();
                out_stream.writeBytes(data);
                //out_stream.writeBytes(ping_msg.toString());
                Log.i("SEARCH", "searching the file :" + data);
                out_stream.flush();
                out_stream.close();
                opStream.close();
                socket.close();

            } catch (UnknownHostException e) {
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                //servSocket.close();
                try {
                    socket.close();
                    servSocket.close();
                } catch (Exception io) {
                    System.out.println("The socket exception");
                }
                Log.e(TAG, "ClientTask socket IOException");
                System.out.println();
                e.printStackTrace();
            }

            return null;
        }
    }

}
