import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.xml.bind.DatatypeConverter;

public class CP2SecStoreClient {
	public static void main(String[] args) throws Exception {
		System.out.println("CP2: trying to connect");

		String hostName = "localhost";

		int portNumber = 4325;

		Socket echoSocket = new Socket();
		SocketAddress sockaddr = new InetSocketAddress(hostName, portNumber);
		echoSocket.connect(sockaddr, 8080);
		System.out.println("connected");
		PrintWriter out = new PrintWriter(echoSocket.getOutputStream(), true);
		BufferedReader in = new BufferedReader(new InputStreamReader(echoSocket.getInputStream()));
		
		//send nonce as the message for the server to encrypt, to make sure no playback attack can take place
		byte[] nonce = new byte[32];
        Random rand;
        rand = SecureRandom.getInstance ("SHA1PRNG");
        rand.nextBytes(nonce);
        String nonceString = new String(nonce, "UTF-16");
        // Send over nonce
        System.out.println("sending over nonce");
        out.println(DatatypeConverter.printBase64Binary(nonce));
        out.flush();

        //receive encrypted nonce from server
		String serverInitialReply = in.readLine();
		System.out.println("gave me secret message: " + serverInitialReply);
		
		//send request for cert and receive signed cert
		String secondMessage = ACs.REQUESTSIGNEDCERT;
		out.println(secondMessage);
		out.flush();
		String sizeInString = in.readLine();
		
		int certificateSize = Integer.parseInt(sizeInString);
		byte[] signedCertificate = new byte[certificateSize];
		String signedCertificateInString = in.readLine();
		signedCertificate = DatatypeConverter.parseBase64Binary(signedCertificateInString);
		System.out.println("gave me signed certificate");
		
		//extract public key from signed certificate
		//creating X509 certificate object
		FileOutputStream fileOutput = new FileOutputStream("CA.crt");
		fileOutput.write(signedCertificate, 0, signedCertificate.length);
        FileInputStream certFileInput = new FileInputStream("CA.crt");

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate CAcert = (X509Certificate) cf.generateCertificate(certFileInput);
						
		//extract public key from the certificate 
		PublicKey CAkey = CAcert.getPublicKey();				
		CAcert.checkValidity();
		System.out.println("public key of CA extracted");
		
		//use public key to decrypt signed certificate to extract public key of server
		Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		cipher.init(Cipher.DECRYPT_MODE, CAkey);
		byte[] decryptedBytes = cipher.doFinal(DatatypeConverter.parseBase64Binary(serverInitialReply));
		String decryptedMessage = new String (decryptedBytes, "UTF-16");
        System.out.println("decryptedMessage: " + decryptedMessage);
        
		//if serverInitialReply is correct, then proceed to give my encrypted client ID
		if (!decryptedMessage.equals(nonceString)){
			out.println(ACs.TERMINATEMSG);
			out.flush();
			out.close();
			in.close();
			echoSocket.close();
			System.out.println("authentication failed");
			return;
		} 
		out.println(ACs.SERVERIDENTIFIED);
		out.flush();
		System.out.println("successfully authenticated the server");
		
		//generate keypair here
		KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
		keyGen.initialize(1024);
		KeyPair keyPair = keyGen.generateKeyPair();
		Key publicKey = keyPair.getPublic();
		Key privateKey = keyPair.getPrivate();
		
		//receive nonce from server
		byte[] serverNonceInBytes = new byte[32];
		String serverNonce = in.readLine();
		serverNonceInBytes = DatatypeConverter.parseBase64Binary(serverNonce);
		System.out.println("received nonce from server: " + serverNonce);
		
		//encrypt nonce using client private key and send it back to server
		Cipher Ecipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		Ecipher.init(Cipher.ENCRYPT_MODE, privateKey);
		byte[] encryptedServerNonce = Ecipher.doFinal(serverNonceInBytes);
		out.println(DatatypeConverter.printBase64Binary(encryptedServerNonce));
		out.flush();	
		System.out.println("sent encrypted nonce to server");

		
		//wait for server to ask for public key, send public key to server
		String requestForPublic = in.readLine();
		if (!requestForPublic.equals(ACs.REQUESTCLIENTPUBLICKEY)){
			out.println("you didn't ask for the public key");
			out.flush();
			out.close();
			in.close();
			echoSocket.close();
			System.out.println("failed to request public key");
			return;
		}

		String encodedKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
		out.println(encodedKey);
		out.flush();
		System.out.println("sent public key to server");
		
		//receive success message and initialise handshake
		String successMessage = in.readLine();
		if (!successMessage.equals(ACs.SERVERREADYTORECEIVE)){
			out.println("you didn't tell me you're ready to receive my files");
			out.flush();
			out.close();
			in.close();
			echoSocket.close();
			return;
		}
		
		System.out.println("initialising handshake");
		
		//generate secret key using AES algorithm, encrypt it with server's public key, send it to server 
		SecretKey key = KeyGenerator.getInstance("AES").generateKey();
		Cipher aesCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		aesCipher.init(Cipher.ENCRYPT_MODE, CAkey);
		byte[] encryptedKey = aesCipher.doFinal(key.getEncoded());
		out.println(DatatypeConverter.printBase64Binary(encryptedKey));
		out.flush();
		System.out.println("finished sending secret symmetric key");
		
		//use server's public key to encrypt the clients files and send it back to server
		for (int i = 0; i < args.length; i++){
			//tell server this is the starting time
			File fileToBeSent = new File(args[i]);
			byte[] fileBytes = new byte[(int)fileToBeSent.length()];
			BufferedInputStream fileInput = new BufferedInputStream(new FileInputStream(fileToBeSent));
			fileInput.read(fileBytes,0,fileBytes.length);
			fileInput.close();
			
			//encrypt this file
			Cipher Ecipher2 = Cipher.getInstance("AES");
			Ecipher2.init(Cipher.ENCRYPT_MODE, key);
			byte[] encryptedFile = encryptFile(fileBytes, Ecipher2);
			
			out.println(args[i]);
			out.println(Integer.toString(encryptedFile.length));
			out.println(DatatypeConverter.printBase64Binary(encryptedFile));
			System.out.println("successfully sent over " + args[i]);
			if((i+1)<args.length) {
				out.println(ACs.CLIENTONEFILESENT);
			}else{
				out.println(ACs.CLIENTDONE);
			}
		}
		System.out.println("told server all encrypted files are sent");
	}

	public static byte[] encryptFile(byte[] fileBytes, Cipher rsaECipher) throws Exception{
		ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();

		int start = 0;
		int fileLength = fileBytes.length;
		while (start < fileLength) {
		  byte[] tempBuff;
		  if (fileLength - start >= 117) {
			  tempBuff = rsaECipher.doFinal(fileBytes, start, 117);
			  //System.out.println(Arrays.toString(tempBuff));
		  } else {
			  tempBuff = rsaECipher.doFinal(fileBytes, start, fileLength - start);
		  }
		  byteOutput.write(tempBuff, 0, tempBuff.length);
		  start += 117;
		}
		byte[] encryptedFileBytes = byteOutput.toByteArray();
		byteOutput.close();
		return encryptedFileBytes;
	}
}