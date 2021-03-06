package com.xrtb.tools;
import java.util.ArrayList;



import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.aerospike.client.AerospikeClient;
import com.aerospike.redisson.RedissonClient;

/**
 * A class that keeps a set of bidders in a scored sorted set. Bidders add themselves to the
 * pool with their timestamp as a score. Once every 5 seconds they wake up and update their own score.
 * Each bidder also ages the set, and anyone who has a timestamp over 60 seconds old is deleted from the set.
 * If a bidder is not in the pool, it is considered down.
 * 
 * @author Ben M. Faul
 *
 */
public class NameNode implements Runnable {

	/** name of the scored sorted set in REDIS. Redisson does not 'directly' support this construct */
	public static final String BIDDERSPOOL = "bidderspool";
	/** Expiry in seconds from now */
	public static final long INTERVAL = 30000;
	/** Time to pause */
	public static final long PAUSE = 5000;
	
	/** The redis connection */
	static RedissonClient redis;
	/** My thread */
	Thread me;
	/** My name. Note, a master has no name, it is null */
	String name;
	/** A latch to keep parent objects from stopping this thread before the redis operations have completed */
	CountDownLatch latch = new CountDownLatch(1);
	
	public static void main(String[] args) throws Exception {
		AerospikeClient spike = new AerospikeClient("localhost",3000);
		redis = new RedissonClient(spike);
		
		System.out.println("Members(0): " + NameNode.getMembers(redis));
		
		NameNode master = new NameNode("localhost",3000);
		NameNode a = new NameNode("a","localhost",3000);
		NameNode b = new NameNode("b","localhost",3000);
		
		
		System.out.println("Members(1): " + NameNode.getMembers(redis));
		Thread.sleep(15000);
		b.stop();
		System.out.println("STOPPED B");
		Thread.sleep(40000);
		System.out.println("Members(n): " + NameNode.getMembers(redis));
	}
	
	/**
	 * Creates a master name node.
	 * @param host String. The host of the redis system.
	 * @param port int. The port for the redis system.
	 * @throws Exception on IO errors.
	 */
	public NameNode(String host, int port) throws Exception {
		this(null,host,port);
	}
	
	/**
	 * Creates a master node.
	 * @param client RedissonClient. The Aerospike redisson object.
	 * @throws Exception if the node fails to start.
	 */
	public NameNode(RedissonClient client) throws Exception {
		redis = client;
		name = null;
		
		me = new Thread(this);
		me.start();
		latch.await();
	}
	
	/**
	 * Creates a named node (a bidder)
	 * @param name String. The instance name of this node.
	 * @param host String. The host address of the redis server.
	 * @param port int. The port number of the redis server. 
	 * @throws Exception on network errors
	 */
	public NameNode(String name, String host, int port) throws Exception {
		AerospikeClient spike = new AerospikeClient(host,port);
		redis = new RedissonClient(spike);
		this.name = name;
		
		me = new Thread(this);
		me.start();
		latch.await();
	}
	
	/**
	 * Creates a named node (a bidder)
	 * @param name String. The instance name of this node.
	 * @param client RedissonClient. An aerospike redisson object.
	 * @throws Exception if class fails to start.
	 */
	public NameNode(String name, RedissonClient client) throws Exception {
		this.name = name;
		redis = client;
		
		me = new Thread(this);
		me.start();
		latch.await();
	}
	
	/**
	 * Remove a name from the pool
	 * @param name String. The name of the bidder to remove.
	 */
	public void remove(String name) throws Exception {
		System.out.println("-------------> REMOVING NAME: " + name);
		redis.zrem(BIDDERSPOOL, name);
		redis.del(name);
	}
	
	/**
	 * Periodic processing
	 */
	public void run() {
		double time = System.currentTimeMillis();
		Map map = new HashMap();
		while(true) {
			try {
			    time = System.currentTimeMillis();
				double now = time;                        // keep a copy
				map.put(name, time);                      // add the timestamp
				
				if (name != null)						  // control nodes don't register
					redis.zadd(BIDDERSPOOL,map);
				
				time = time - INTERVAL * 2;                       							// find all members whose score is stake
				List<String> candidates = redis.zrangeByScore(BIDDERSPOOL, 0, time);
				
				if (candidates == null) {  // aerospike is running, but, there is no BIDDERSPOOL initialize yet
					latch.countDown();
					return;
				}
				
				long k = redis.zremrangeByScore(BIDDERSPOOL, 0, time);     				// and remove them
				if (k > 0) {
					log(3,"NameNodeManager","Removed stale bidders: " + candidates);
				}
				
			} catch (Exception e) {
				e.printStackTrace();
				log(1,"NameNodeManager", "INTERRUPT: " + name);
				System.exit(0);
				//if (name != null)
				//	redis.zrem(BIDDERSPOOL, name);
				//return;
			}
			latch.countDown();						// doesn't do anything after the first time
			try {
				Thread.sleep(PAUSE);
			} catch (InterruptedException e) {
				return;
			}
			
		}
	}
	
	/**
	 * A simple logger. Override with your log mechanism.
	 * @param level int. The logging level.
	 * @param location String. The location of where the log originated.
	 * @param msg String. The message of the log.
	 */
	public void log(int level, String location, String msg) {
		String name = "Master";
		if (this.name != null)
			name = this.name;
		System.out.println(name + ": " + location + " : " + msg);
	}
	
	/**
	 * Get a list of bidders on the system.
	 * @return List. A list of bidders by their instance names.
	 * @throws Exception on Redis errors (except cast error when the key is empty)
	 */
	public  List<String> getMembers()  throws Exception {
		try {
			return getMembers(redis);
		} catch (Exception error) {
			if (error.toString().contains("Long cannot be cast to java.util.List")) {
                return (List<String>) new ArrayList();
			}
			throw error;
		}
	}
	
	/**
	 * A static members retrieval of bidders.
	 * @param redis Jedis. The Jedis object.
	 * @return List. A list of bidders by their instance names.
	 * @throws Exception on Jedis exceptions.
	 */
	public static List<String> getMembers(RedissonClient redis)  {
		double now = System.currentTimeMillis() + 100000;
		return redis.zrangeByScore(BIDDERSPOOL, 0, now);
	}
	
	/**
	 * Stops this thread.
	 */
	public void stop() {
		me.interrupt();
	}
	
	/**
	 * Stop this thread
	 */
	public void halt() {
		stop();
	}
	
	/** 
	 * Remove yourself from the pool
	 * 
	 */
	public void removeYourself() {
		redis.zrem(BIDDERSPOOL, name);
	}
}
