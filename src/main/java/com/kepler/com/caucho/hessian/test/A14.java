package com.kepler.com.caucho.hessian.test;

/**
 * Empty object for short-encoding testing
 */
public class A14 implements java.io.Serializable {
  public boolean equals(Object v)
  {
    return v != null && getClass().equals(v.getClass());
  }
  
  public String toString()
  {
    return getClass().getName() + "[]";
  }
}
