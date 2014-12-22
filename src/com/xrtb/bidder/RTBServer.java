package com.xrtb.bidder;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.Scanner;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.xrtb.commands.Echo;
import com.xrtb.common.Campaign;
import com.xrtb.common.Configuration;
import com.xrtb.exchanges.Nexage;
import com.xrtb.pojo.BidRequest;
import com.xrtb.pojo.BidResponse;
import com.xrtb.pojo.NoBid;
import com.xrtb.pojo.WinObject;

/**
 * A JAVA based RTB2.1 server.
 * 
 * @author Ben M. Faul
 * 
 */
public class RTBServer implements Runnable {
	public static final String SIMULATOR_URL = "/xrtb/simulator/exchange";
	public static final String SIMULATOR_ROOT = "web/exchange.html";
	public static final int BID_CODE = 200; // http code ok
	public static final int NOBID_CODE = 204; // http code no bid

	public static int percentage = 100; // throttle is wide open at 100, closed
										// at 0
	public static boolean stopped = false; // is the server not accepting bid
											// requests?

	public static long bid = 0; // number of bids processed
	public static long nobid = 0; // number of nobids processed
	public static Configuration config;

	int port = 8080;
	Thread me;

	CampaignSelector campaigns = CampaignSelector.getInstance(); // used to
																	// select
																	// campaigns
																	// against
																	// bid
																	// requests

	/**
	 * This is the entry point for the RTB server.
	 * 
	 * @param args
	 *            . String[]. Config file name. If not present, uses default and
	 *            port 8080.
	 * @throws Exception. Throws
	 *             exceptions on json parse errors.
	 */
	public static void main(String[] args) throws Exception {
		config = Configuration.getInstance();
		config.clear();
		if (args.length == 0)
			config.initialize("Campaigns/payday.json");
		else
			config.initialize(args[0]);
		new RTBServer();
	}

	/**
	 * Class instantiator for use without configuration
	 * 
	 * @param port
	 *            int. The port of this server.
	 * @throws Exception. Throws
	 *             exceptions on JETTY errors.
	 */
	public RTBServer() throws Exception {
		this.port = port;
		Controller.getInstance();
		me = new Thread(this);
		me.start();
		Thread.sleep(500);
	}

	/**
	 * Return the campaign selector object.
	 * 
	 * @return CampaignSelector. The object used to select campaigns when bid
	 *         requests come in
	 */
	public CampaignSelector getCampaigns() {
		return campaigns;
	}

	@Override
	public void run() {
		Server server = new Server(port);
		server.setHandler(new Handler());

		try {
			Controller.getInstance().sendLog(
					0,
					"{\"message\":\"System initialized\", \"port\":" + port
							+ "}");
			server.start();
			server.join();
		} catch (Exception error) {

		}
	}

	/**
	 * Stop the Jetty server
	 */
	public void halt() {
		try {
			me.interrupt();
		} catch (Exception error) {

		}
		Controller.getInstance()
				.sendLog(0, "{\"message\":\"System shutdown\"}");
	}

	/**
	 * Returns the status of this server.
	 * 
	 * @return Map. A map representation of this status.
	 */
	public static Echo getStatus() {
		Echo e = new Echo();
		;
		e.percentage = percentage;
		e.stopped = stopped;
		e.bid = bid;
		e.nobid = nobid;
		e.campaigns = Configuration.getInstance().campaignsList;

		return e;
	}
}

/**
 * JETTY handler for incoming bid request.
 * 
 * This handler processes RTB2.1 bid requests.
 * 
 * @author Ben M. Faul
 * 
 */
class Handler extends AbstractHandler {
	Random rand = new Random();

	@Override
	public void handle(String target, Request baseRequest,
			HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		InputStream body = request.getInputStream();
		BidRequest br = null;
		String json = "{}";
		String id = "";
		Campaign campaign = null;
		int code = RTBServer.BID_CODE;
		long time = System.currentTimeMillis();

		try {

			// ////////////// Simulator Service ////////////////////////////

			if (target.contains("favicon")) {
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println("");
				return;
			}

			if (target.contains(RTBServer.SIMULATOR_URL)) {
				String page = Charset
						.defaultCharset()
						.decode(ByteBuffer.wrap(Files.readAllBytes(Paths
								.get(RTBServer.SIMULATOR_ROOT)))).toString();

				response.setContentType("text/html");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(page);
				return;
			}
			if (target.contains("web/")) {
				int i = target.indexOf("web");
				target = target.substring(i);
				Scanner in = new Scanner(new FileReader(target));
				String jquery = "";
				while (in.hasNextLine()) {
					jquery += in.nextLine();
				}
				response.setContentType("text/javascript;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(jquery);
				return;
			}

			// //////////////////////////////////////////////////////////////////////
			if (target.contains("/rtb/win/nexage")) {
				json = WinObject.getJson(target);
				response.setContentType("application/json;charset=utf-8");
				response.setStatus(HttpServletResponse.SC_OK);
				baseRequest.setHandled(true);
				response.getWriter().println(json);
				return;
			}
		} catch (Exception err) {
			err.printStackTrace();
			return;
		}

		try {
			if (target.contains("/rtb/bids/nexage"))
				br = new Nexage(body);

			if (br == null) {
				json = handleNoBid(id, "Wrong target: " + target);
				code = RTBServer.NOBID_CODE;
			} else {
				id = br.getId();
				if (CampaignSelector.getInstance().size() == 0) {
					json = handleNoBid(id, "No campaigns loaded");
					code = RTBServer.NOBID_CODE;
				} else if (RTBServer.stopped) {
					json = handleNoBid(id, "Server stopped");
					code = RTBServer.NOBID_CODE;
				} else if (!checkPercentage()) {
					json = handleNoBid(id, "Server throttled");
					code = RTBServer.NOBID_CODE;
				} else {
					BidResponse bresp = CampaignSelector.getInstance().get(br);
					if (bresp == null) {
						json = handleNoBid(id, "No matching campaign");
						code = RTBServer.NOBID_CODE;
						RTBServer.nobid++;
					} else {
						json = bresp.toString();
						code = RTBServer.BID_CODE;
						RTBServer.bid++;
					}
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			json = handleNoBid("", e.toString());
			code = RTBServer.NOBID_CODE;
		}

		time = System.currentTimeMillis() - time;

		response.setHeader("X-TIME", "" + time);
		response.setContentType("application/json;charset=utf-8");
		response.setStatus(HttpServletResponse.SC_OK);
		baseRequest.setHandled(true);
		response.getWriter().println(json);
	}

	/**
	 * Checks to see if the bidder wants to bid on only a certain percentage of
	 * bid requests coming in - a form of throttling.
	 * 
	 * @return boolean. True means try to bid, False means don't bid
	 */
	boolean checkPercentage() {
		if (RTBServer.percentage == 100)
			return true;
		int x = rand.nextInt(101);
		if (x < RTBServer.percentage)
			return true;
		return false;
	}

	/**
	 * Creates a no bid object.
	 * 
	 * @param id
	 *            . String. The id of the bid request.
	 * @param reason
	 *            . String. The reason why the bidder did not bid.
	 * @return String. The JSON of the no bid object.
	 */
	public String handleNoBid(String id, String reason) {
		NoBid nb = new NoBid(id, reason);
		return nb.toString();
	}
}