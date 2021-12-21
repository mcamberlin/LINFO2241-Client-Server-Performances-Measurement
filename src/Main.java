import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.*;
import java.net.Socket;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.Random;


//TODO: TASK 2
//TODO: implement a small client ap sending request at random times (choose some distribution) : Corentin
//TODO: make some measurements with different request rate, difficulities : Corentin
//TODO: compare with the 2 implementations of the bruteforce: Merlin

//TODO: TASK 3


public class Main
{
    // CLIENT PARAMETERS
    private static final int nbRequest = 3;

    // STATIC VARIABLES AND FUNCTIONS
    // Streams variables
    private static InputStream inputStream = null;
    private static DataInputStream dataInputStream = null;
    private static OutputStream outputStream = null;
    private static DataOutputStream dataOutputStream = null;
    private static final HashMap<Integer, Long> startTimes = new HashMap<>();  // (requestID, send time)

    // Variables related to encryption
    private static final String[] passwords = {"abc", "abcd", "abcde", "abcdef", "abcdefg"};
    private static final String srcFolderToEncrypt = "files/Files-5MB/";
    private static final String destFolderEncrypted = "files-encrypted/Files-5MB/";

    private static final int foldIdx = 0;
    private static final int maxRequestNum = 255;
    private static final Encryptor.Folder foldToSend = Encryptor.folders[foldIdx];


    // STATIC FUNCTIONS
    /**
     * This function hashes a string with the SHA-1 algorithm
     * @param data The string to hash
     * @return An array of 20 bytes which is the hash of the string
     */
    public static byte[] hashSHA1(String data) throws NoSuchAlgorithmException
    {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data.getBytes());
    }



    public static void main(String[] args)
    {
        Encryptor.main(foldIdx);
        // Connection between server and client
        Socket clientSocket = null;
        try
        {
            clientSocket = new Socket(ServerMain.getIpAddress(), ServerMain.getPortNumber());
        }
        catch (IOException e)
        {
            e.printStackTrace();
            System.err.println("Failed to connect to server");
            System.exit(-1);
        }
        System.out.println("Socket created");

        // Streams creation
        try
        {
            // Stream to write request to socket
            outputStream = clientSocket.getOutputStream();
            dataOutputStream = new DataOutputStream(outputStream);

            // Stream to read response from socket
            inputStream = clientSocket.getInputStream();
            dataInputStream = new DataInputStream(inputStream);
        }
        catch (IOException e)
        {
            System.err.println("Could not create required streams");
            System.exit(-1);
        }


        // Sender thread: send requests to the server
        ClientSender clientSender = new ClientSender();
        clientSender.start();

        // Receiver thread: handle response of previous sent requests
        ClientReceiver clientReceiver = new ClientReceiver();
        clientReceiver.start();
    }

    private static class ClientSender extends Thread
    {
        // STATIC FUNCTION
        /**
         * This function is used by a client to send the information needed by the server to process the file
         * @param out Socket stream connected to the server where the data are written
         * @param requestId unique ID of the request
         * @param hashPwd SHA-1 hash of the password used to derive the key of the encryption
         * @param pwdLength Length of the clear password
         * @param fileLength Length of the encrypted file
         */
        public static void sendRequest(DataOutputStream out, int requestId, byte[] hashPwd, int pwdLength, long fileLength)
                throws IOException
        {
            out.writeInt(requestId);
            out.write(hashPwd,0, 20);
            out.writeInt(pwdLength);
            out.writeLong(fileLength);
        }


        /**
         * Returns a random double sample following a normal distribution
         * @param mean mean of the normal distribution
         * @param std standard deviation of the normal
         */
        public static double gaussian(double mean, double std) {
            Random rnd = new Random();
            return (rnd.nextGaussian()*std) + mean;
        }

        /**
         * Returns a random double sample following a normal exponential
         * @param mean mean of the exponential distribution
         */
        public static double nextExp(double mean) {
            Random rnd = new Random();
            return -mean*Math.log(rnd.nextDouble());
        }

        /**
         * Returns current time in seconds
         */
        public static double getCurrentTime() {
            return System.currentTimeMillis()/1000.0;
        }

        public ClientSender()
        {
            super("ClientSenderThread");
        }

        @Override
        public void run()
        {
            System.out.println("Run ClientSender");
            try
            {
                int fileIdx = 1;
                int requestId = 1;
                double inter_request_time = nextExp(0.5);  // Mean of 2 seconds
                double start_time = getCurrentTime(); // Start time in seconds
                double deltaTime;
                while (true)
                {
                    deltaTime = getCurrentTime() - start_time;
                    if (deltaTime >= inter_request_time) {
                        String password = foldToSend.passwords[fileIdx-1];
                        File encryptedFile = new File(foldToSend.getPath("files-encrypted", fileIdx));
                        InputStream inFile = new FileInputStream(encryptedFile);

                        // SEND THE PROCESSING INFORMATION AND FILE
                        byte[] hashPwd = hashSHA1(password);
                        int pwdLength = password.length();
                        long fileLength = encryptedFile.length();

                        sendRequest(dataOutputStream, requestId, hashPwd, pwdLength, fileLength);
                        dataOutputStream.flush();
                        FileManagement.sendFile(inFile, dataOutputStream);
                        startTimes.put(requestId, System.currentTimeMillis());
                        System.out.println("Client sends : (requestId, hashPwd, pwdLength, fileLength) = (" + requestId + ", " + hashPwd + ", " + pwdLength + ", " + fileLength + ")");

                        inter_request_time = nextExp(0.5);
                        start_time = getCurrentTime();

                        requestId = (requestId % maxRequestNum) + 1;
                        fileIdx = (fileIdx % foldToSend.nbFiles) + 1;
                        inFile.close();
                    }
                }
            }
            catch (NoSuchAlgorithmException | IOException e)
            {
                e.printStackTrace();
            }

            System.out.println("End ClientSender");
        }
    }

    private static class ClientReceiver extends Thread
    {
        public ClientReceiver()
        {
            super("ClientReceiverThread");
        }

        @Override
        public void run()
        {
            System.out.println("Run ClientReceiver");
            try
            {
                while(true)
                {
                    int requestId = dataInputStream.readInt();
                    long fileLengthServer = dataInputStream.readLong();
                    System.out.println("Client receives : (requestId, fileLength) = (" + requestId + ", " + fileLengthServer + ")");
                    File decryptedClient = new File("tmp/file-" + requestId % foldToSend.nbFiles + "-decrypted-client" + ".bin");
                    OutputStream outFile = new FileOutputStream(decryptedClient);
                    FileManagement.receiveFile(inputStream, outFile, fileLengthServer);
                    long deltaTime = System.currentTimeMillis() - startTimes.get(requestId);
                    System.out.println("Time observed by the client "+requestId+": " + deltaTime + "ms");
                    //outFile.close();
                }

            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            System.out.println("End ClientReceiver");
        }
    }
}