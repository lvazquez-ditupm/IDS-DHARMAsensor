package main.controllers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.logging.Logger;
import main.dao.ProfileDao;
import main.dao.SnapshotDao;
import main.entities.Alert;
import main.entities.Anomaly;
import main.entities.Statistics;
import main.utils.DataBaseConnection;
import main.utils.RunnableTask;
import main.utils.Task;

public class AlertHandler extends Task implements RunnableTask {

	private final String hostName = "138.4.7.191";
	private final int portNumber = 4040;
	private static Logger LOGGER = Logger.getLogger(Thread.currentThread()
			.getStackTrace()[0].getClassName());
	private static final String FIFO = "/var/log/suricata/fast.pipe";

	private volatile boolean running;

	private BufferedReader in;

	private PradsController prads;

	private DataBaseConnection dbCon;
	private ProfileDao profileDao;
	private SnapshotDao snapshotDao;

	private Statistics profile;
	private Statistics snapshot;

	public AlertHandler() {
		this.prads = new PradsController();
		this.dbCon = new DataBaseConnection("cxtracker", "cxtracker",
				"cxtracker");
	}

	public void run() {
		async();
	}

	@Override
	public void async() {

		running = true;

		this.dbCon.connect();
		this.profileDao = new ProfileDao(dbCon.getConn());
		this.snapshotDao = new SnapshotDao(dbCon.getConn());

		prads.start();

		System.out.println();

		try {

			in = new BufferedReader(new FileReader(FIFO));

			while (running) {

				String line;

				if ((line = in.readLine()) != null) {

					int counter = 0;

					Alert alert = new Alert(line);
										
					System.out.print("[acs-1] Alert recieved: "
							+ alert.getMessage() + " at: [UTC] "
							+ alert.getDate());
										
					sleep(100); // give prads a little time to save connections

					PradsController.stopPrads();
					
					/* EXTRACT PROFILE DATA */
					if (profileDao.isProfileDataEnough()) {
						profile = profileDao.getProfile();
					} else {
						System.out.print("[P]");
						profile = profileDao.getFullProfile();
					}

					/* WAIT FOR SNAPSHOT */
					while (!snapshotDao.isSnapshotReady(alert.getDate())
							&& running) {
						if (counter == 20 && snapshot != null) {
							System.out.print("[S]");
							break;
						}
						sleep(50);
						counter++;
					}

					if (!running) {
						break;
					}

					/* EXTRACT SNAPSHOT DATA */
					snapshot = snapshotDao.getSnapshot(alert.getDate());

					long netAnomaly = Math
							.round((new Anomaly(profile, snapshot))
									.getAnomaly());

					System.out.print(" ---> Network ANOMALY of: " + netAnomaly + "/100");
					
					sendToSocket(alert.getAlert(), netAnomaly, hostName, portNumber);

					PradsController.startPrads();
				}
			}
		} catch (IOException ex) {
			LOGGER.warning(ex.getMessage());
			System.exit(-1);
		} finally {
			dbCon.close();
		}
	}

	private void sendToSocket(String alert, long netAnomaly, String hostName, int portNumber) {		
		alert = "IDS///"+alert;
		byte[] buffer = alert.getBytes();
		InetAddress address;
		try {
			address = InetAddress.getByName(hostName);
	        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, portNumber);
	        DatagramSocket datagramSocket = new DatagramSocket();
	        datagramSocket.send(packet);
	        datagramSocket.close();	
	        
	        String netAnom = "NetAnom///"+String.valueOf(netAnomaly);
	        buffer = netAnom.getBytes();
			address = InetAddress.getByName(hostName);
	        packet = new DatagramPacket(buffer, buffer.length, address, portNumber);
	        datagramSocket = new DatagramSocket();
	        datagramSocket.send(packet);
	        datagramSocket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}		
	}

	@Override
	public void sync(Object lock) {/* ignored */
	}

	public void stop() {
		running = false;
		prads.stop();
	}
}
