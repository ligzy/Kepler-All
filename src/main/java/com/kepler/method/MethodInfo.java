package com.kepler.method;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * @author KimShen
 *
 */
public class MethodInfo {

	private final Class<?>[] classes;

	private final boolean wrapper;

	private final String[] names;

	private final Method method;

	public MethodInfo(boolean wrapper, Class<?>[] classes, String[] names, Method method) {
		super();
		this.classes = classes;
		this.wrapper = wrapper;
		this.method = method;
		this.names = names;
	}

	public MethodInfo(Class<?>[] classes, String[] names, Method method) {
		this(false, classes, names, method);
	}

	public Object[] args(Map<String, Object> param) {
		if (this.names == null) {
			return null;
		}
		if (this.names.length == 0) {
			return null;
		}
		if (this.wrapper) {
			return new Object[] { param };
		}
		Object[] args = new Object[this.names.length];
		for (int index = 0; index < this.names.length; index++) {
			args[index] = param.get(this.names[index]);
		}
		return args;
	}

	public Class<?>[] classes() {
		return this.classes;
	}

	public String[] names() {
		return this.names;
	}

	public Method method() {
		return this.method;
	}
}
