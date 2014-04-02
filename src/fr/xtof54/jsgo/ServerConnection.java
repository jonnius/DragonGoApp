package fr.xtof54.jsgo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import net.engio.mbassy.listener.Handler;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONObject;
import fr.xtof54.jsgo.EventManager.eventType;

/**
 * This class is designed to be as much independent on the other
 * libraries than possible. In particular, it does not depend on the
 * android library, and can thus be used in desktop-oriented application.
 * This is also why it defines 2 interfaces.
 * 
 * Its role is to manage all communication during the game with one Dragon go net server.
 * Note that you can create several objects of this class to support several servers.
 * 
 * @author xtof
 *
 */
public class ServerConnection {
	final static String cmdGetListOfGames = "quick_do.php?obj=game&cmd=list&view=status";

	final String netErrMsg = "Connection errors or timeout, you may retry";

	private String u,p,server;
	private HttpClient httpclient=null;

	@Handler
	public void handleMessage(EventManager.EventRequestLogin msg) {
	    System.out.println("handle event "+msg);
	}
	
	/**
	 * We define this interface so that all logging info can be simply displayed on the console,
	 * or shown as Android Toasts !
	 * Note that this interface really aims at showing routine messages to the user, and is thus quite different
	 * from the traditional logging facilities that rather aims at errors, warnings...
	 * @author xtof
	 *
	 */
	public interface DetLogger {
		public void showMsg(String s);
	}
	private DetLogger logger = new DetLogger() {
		@Override
		public void showMsg(String s) {
			System.out.println(s);
		}
	};
	public void setLogget(DetLogger l) {logger=l;}


	final String[] serverNames = {
			"http://www.dragongoserver.net/",
			"http://dragongoserver.sourceforge.net/"
	};

	public void closeConnection() {
		if (httpclient!=null)
			httpclient.getConnectionManager().shutdown();
		httpclient=null;
	}

	/**
	 * creates a connection to a specific server;
	 * determines the correct credentials
	 * @param num
	 */
	public ServerConnection(int num, String userlogin, String userpwd) {
		server=serverNames[num];
		// TODO: this must be handled outside of this class
		//		String tu = PrefUtils.getFromPrefs(GoJsActivity.main, PrefUtils.PREFS_LOGIN_USERNAME_KEY,null);
		//	    String tp = PrefUtils.getFromPrefs(GoJsActivity.main, PrefUtils.PREFS_LOGIN_PASSWORD_KEY,null);
		//	    if (tu==null||tp==null) {
		//	    	logger.showMsg("Please enter your credentials first via menu Settings");
		//	        return;
		//	    }
		//		if (GoJsActivity.main.debugdevel==0) {
		//			GoJsActivity.main.debugdevel=1;
		//		} else if (GoJsActivity.main.debugdevel==1) {
		//			tu = PrefUtils.getFromPrefs(GoJsActivity.main, PrefUtils.PREFS_LOGIN_USERNAME2_KEY,null);
		//			tp = PrefUtils.getFromPrefs(GoJsActivity.main, PrefUtils.PREFS_LOGIN_PASSWORD2_KEY,null);
		//			GoJsActivity.main.debugdevel=0;
		//		}
		u=userlogin; p=userpwd;
		
		final EventManager em = EventManager.getEventManager();
		em.bus.subscribe(this);
	}

	/**
	 * Login to this server
	 * WARNING: asynchronous / non-blocking !!
	 */
	public boolean loginok=false;
	public void startLogin() {
	    final EventManager em = EventManager.getEventManager();
	    em.sendEvent(eventType.loginStarted);

		class MyRunnable implements Runnable {
			@Override
			public void run() {
				System.out.println("start login run");
				HttpParams httpparms = new BasicHttpParams();
				HttpConnectionParams.setConnectionTimeout(httpparms, 6000);
				HttpConnectionParams.setSoTimeout(httpparms, 6000);
				httpclient = new DefaultHttpClient(httpparms);
				try {
					String cmd = server+"login.php?quick_mode=1&userid="+u+"&passwd="+p;
					System.out.println("debug login cmd "+cmd);
					HttpGet httpget = new HttpGet(cmd);
					HttpResponse response = httpclient.execute(httpget);
					Header[] heds = response.getAllHeaders();
					for (Header s : heds)
						System.out.println("[HEADER] "+s);
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						InputStream instream = entity.getContent();
						BufferedReader fin = new BufferedReader(new InputStreamReader(instream, Charset.forName("UTF-8")));
						for (;;) {
							String s = fin.readLine();
							if (s==null) break;
							System.out.println("LOGINlog "+s);
							if (s.contains("#Error"))
								logger.showMsg("Error login; check credentials");
						}
						fin.close();
					}
					loginok=true;
				} catch (Exception e) {
					e.printStackTrace();
					//				    if (s.contains("#Error")) logger.showMsg("Error login; check credentials");
					logger.showMsg(netErrMsg);
					loginok=false;
				}
				System.out.println("end login run");
				em.sendEvent(eventType.loginEnd);
			}
		};
		MyRunnable r = new MyRunnable();
		Thread loginthread = new Thread(r);
		loginthread.start();
	}

	public JSONObject o=null;
	/**
	 * send a command to the server and gets back a JSon object with the answer
	 * 
	 * WARNING: asynchronous / non-blocking !!
	 * @param cmd
	 * @return
	 */
	public void sendCmdToServer(final String cmd, final eventType startEvent, final eventType endEvent) {
		System.out.println("begin send command, httpclient="+httpclient);
		final EventManager em = EventManager.getEventManager();
		if (startEvent!=null) em.sendEvent(startEvent);
		if (httpclient==null) {
			System.out.println("in sendcmd: no httpclient, trying login...");
			em.registerListener(eventType.loginEnd, new EventManager.EventListener() {
				@Override
				public void reactToEvent() {
					em.unregisterListener(eventType.loginEnd, this);
					if (loginok) sendCmdToServer(cmd,null,endEvent);
					else if (endEvent!=null) em.sendEvent(endEvent);
				}
			});
			startLogin();
			return;
		}
		System.out.println("now httpclient="+httpclient);
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					System.out.println("debug send cmd "+server+cmd);
					HttpGet httpget = new HttpGet(server+cmd);
					HttpResponse response = httpclient.execute(httpget);
					Header[] heds = response.getAllHeaders();
					for (Header s : heds)
						System.out.println("[HEADER] "+s);
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						InputStream instream = entity.getContent();
						BufferedReader fin = new BufferedReader(new InputStreamReader(instream, Charset.forName("UTF-8")));
						for (;;) {
							String s = fin.readLine();
							if (s==null) break;
							System.out.println("cmdlog "+s);
							s=s.trim();
							if (s.length()>0 && s.charAt(0)=='{') {
								o = new JSONObject(s);
								break;
							}
						}
						fin.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
					logger.showMsg(netErrMsg);
				}
				System.out.println("server runnable terminated");
				if (endEvent!=null) em.sendEvent(endEvent);
			}
		};
		o=null;
		Thread t = new Thread(r);
		t.start();
		//    	if (GoJsActivity.main!=null) {
		//    		Thread cmdthread = GoJsActivity.main.runInWaitingThread(r);
		//    	} else {
		//    		Thread cmdthread = new Thread(r);
		//    		cmdthread.start();
		//    	}
	}

	public List<String> sgf = null;
	/**
	 * because download sgf does not return a JSON object, we have to use a dedicated function to do it
	 * @param gameid
	 * @param sendEvent=true when called normally
	 * @return
	 */
	public void downloadSgf(final int gameid, boolean sendEvent) {
		final EventManager em = EventManager.getEventManager();
		if (sendEvent) em.sendEvent(eventType.downloadGameStarted);
		if (httpclient==null) {
			System.out.println("in getsgf: no httpclient, trying login...");
			em.registerListener(eventType.loginEnd, new EventManager.EventListener() {
				@Override
				public void reactToEvent() {
					em.unregisterListener(eventType.loginEnd, this);
					if (loginok) downloadSgf(gameid, false);
					else em.sendEvent(eventType.downloadGameEnd);
				}
			});
			startLogin();
			return;
		}
		sgf = new ArrayList<String>();
		Runnable r = new Runnable() {
			@Override
			public void run() {
				try {
					final String cmd = server+"sgf.php?gid="+gameid+"&owned_comments=1&quick_mode=1";
					HttpGet httpget = new HttpGet(cmd);
					HttpResponse response = httpclient.execute(httpget);
					Header[] heds = response.getAllHeaders();
					for (Header s : heds)
						System.out.println("[HEADER] "+s);
					HttpEntity entity = response.getEntity();
					if (entity != null) {
						InputStream instream = entity.getContent();
						BufferedReader fin = new BufferedReader(new InputStreamReader(instream, Charset.forName("UTF-8")));
						for (;;) {
							String s = fin.readLine();
							if (s==null) break;
							s=s.trim();
							if (s.length()>0&&s.charAt(0)!='[') {
								sgf.add(s);
							}
							System.out.println("SGFdownload "+s);
						}
						fin.close();
					}
				} catch (Exception e) {
					e.printStackTrace();
					logger.showMsg(netErrMsg);
				}
				em.sendEvent(eventType.downloadGameEnd);
			}
		};
		Thread t = new Thread(r);
		t.start();
	}

	static String[] loadCredsFromFile(String file) {
		String u=null, p=null;
		try {
			BufferedReader f=new BufferedReader(new FileReader(file));
			u=f.readLine();
			p=f.readLine();
			f.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		String[] res = {u,p};
		return res;
	}

	public static void main(String args[]) throws Exception {
		final String[] c = loadCredsFromFile("creds.txt");
		ServerConnection server = new ServerConnection(0,c[0],c[1]);
		final EventManager em = EventManager.getEventManager();
		em.registerListener(eventType.downloadListEnd, new EventManager.EventListener() {
			@Override
			public void reactToEvent() {
				synchronized (c) {
					c.notifyAll();
				}
			}
		});
		server.sendCmdToServer(cmdGetListOfGames,eventType.downloadListStarted, eventType.downloadListEnd);
		synchronized (c) {
			c.wait();
		}
		JSONObject o = server.o;
		System.out.println("answer: "+o);
		server.closeConnection();
	}
}
