package com.kepler.transaction;

import java.util.List;

/**
 * 事务持久化
 * 
 * @author KimShen
 *
 */
public interface Persistent {
	
	/**
	 * 获取尚未删除持久化事务请求
	 * 
	 * @return
	 */
	public List<Request> list();

	/**
	 * 删除持久化事务请求
	 * 
	 * @param uuid 事务号
	 * @return 是否成功删除持久化事务请求
  	 * @throws Exception 执行异常
	 */
	public void release(String uuid) throws Exception;

	/**
	 * 持久化事务请求
	 * 
	 * @param request
	 * @return 如果持久化成功则返回True
  	 * @throws Exception 执行异常
	 */
	public void persist(Request request) throws Exception;
}