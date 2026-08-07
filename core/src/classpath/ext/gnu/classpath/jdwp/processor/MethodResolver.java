/* MethodResolver.java -- shared utility for resolving methods across VmType hierarchy
   Copyright (C) 2005 Free Software Foundation

This file is part of GNU Classpath.

GNU Classpath is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2, or (at your option)
any later version.

GNU Classpath is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Classpath; see the file COPYING.  If not, write to the
Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

*/

package gnu.classpath.jdwp.processor;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmNormalClass;
import org.jnode.vm.classmgr.VmType;

/**
 * Shared utility for resolving methods across the VmType hierarchy.
 * 
 * Used by both ClassTypeCommandSet (for static methods/constructors)
 * and ObjectReferenceCommandSet (for instance methods, excluding constructors).
 */
class MethodResolver
{
  /**
   * Collect all types in the superclass chain starting from vmType.
   */
  static List<VmType> collectHierarchy(VmType vmType)
  {
    List<VmType> types = new ArrayList<VmType>();
    VmType cur = vmType;
    while (cur != null)
      {
        types.add(cur);
        if (cur instanceof VmNormalClass)
          {
            cur = ((VmNormalClass) cur).getSuperClass();
          }
        else
          {
            break;
          }
      }
    return types;
  }

  /**
   * Get parameter count from a Member (handles both Method and Constructor).
   */
  static int memberParamCount(Member member)
  {
    if (member instanceof Method)
      {
        return ((Method) member).getParameterTypes().length;
      }
    else if (member instanceof java.lang.reflect.Constructor)
      {
        return ((java.lang.reflect.Constructor<?>) member).getParameterTypes().length;
      }
    return 0;
  }

  /**
   * Walk the VmType hierarchy (superclass chain + interfaces) looking for a
   * member at the given index with the correct parameter count.
   * 
   * @param vmType Starting type
   * @param methodIdx Index in the method table
   * @param paramCount Expected parameter count
   * @param constructorsAccepted If false, constructors are excluded (for instance methods)
   * @return The resolved member, or null if not found
   */
  static Member findByIndex(VmType vmType, long methodIdx, int paramCount,
                            boolean constructorsAccepted)
  {
    List<VmType> types = collectHierarchy(vmType);

    // Search each type's declared methods
    for (VmType searchType : types)
      {
        int nMethods = searchType.getNoDeclaredMethods();
        if (methodIdx >= 0 && methodIdx < nMethods)
          {
            VmMethod vmMethod = searchType.getDeclaredMethod((int) methodIdx);
            if (vmMethod != null)
              {
                if (!constructorsAccepted && vmMethod.isConstructor())
                  {
                    continue;
                  }
                Member candidate = vmMethod.asMember();
                int pCount = memberParamCount(candidate);
                if (pCount == paramCount)
                  {
                    return candidate;
                  }
              }
          }
      }

    // Search interfaces
    for (VmType searchType : types)
      {
        int nInterfaces = searchType.getNoInterfaces();
        for (int i = 0; i < nInterfaces; i++)
          {
            VmType iface = searchType.getInterface(i);
            if (iface != null)
              {
                int nMethods = iface.getNoDeclaredMethods();
                if (methodIdx >= 0 && methodIdx < nMethods)
                  {
                    VmMethod vmMethod = iface.getDeclaredMethod((int) methodIdx);
                    if (vmMethod != null)
                      {
                        if (!constructorsAccepted && vmMethod.isConstructor())
                          {
                            continue;
                          }
                        Member candidate = vmMethod.asMember();
                        int pCount = memberParamCount(candidate);
                        if (pCount == paramCount)
                          {
                            return candidate;
                          }
                      }
                  }
              }
          }
      }

    return null;
  }

  /**
   * Fallback: walk Java reflection getDeclaredMethods across the full class
   * hierarchy including interfaces.
   * 
   * @param clazz Starting class
   * @param methodIdx Index in the method table
   * @param paramCount Expected parameter count
   * @param constructorsAccepted If false, constructors are excluded
   * @return The resolved member, or null if not found
   */
  static Member findByReflection(Class clazz, long methodIdx, int paramCount,
                                 boolean constructorsAccepted)
  {
    // Collect all classes in the superclass chain
    List<Class> classes = new ArrayList<Class>();
    Class cur = clazz;
    while (cur != null)
      {
        classes.add(cur);
        cur = cur.getSuperclass();
      }

    // Search each class
    for (Class c : classes)
      {
        Member[] declared = c.getDeclaredMethods();
        if (methodIdx >= 0 && methodIdx < declared.length)
          {
            Member candidate = declared[(int) methodIdx];
            if (!constructorsAccepted && candidate instanceof java.lang.reflect.Constructor)
              {
                continue;
              }
            int pCount = memberParamCount(candidate);
            if (pCount == paramCount)
              {
                return candidate;
              }
          }
      }

    // Also search interfaces declared by each class
    for (Class c : classes)
      {
        Class[] ifaces = c.getInterfaces();
        for (Class iface : ifaces)
          {
            Member[] declared = iface.getDeclaredMethods();
            if (methodIdx >= 0 && methodIdx < declared.length)
              {
                Member candidate = declared[(int) methodIdx];
                if (!constructorsAccepted && candidate instanceof java.lang.reflect.Constructor)
                  {
                    continue;
                  }
                int pCount = memberParamCount(candidate);
                if (pCount == paramCount)
                  {
                    return candidate;
                  }
              }
          }
      }

    return null;
  }

  /**
   * Last-resort fallback: use the method name from the original VmMethod entry
   * and search the full hierarchy (including interfaces) by name + param count.
   * 
   * @param vmType Starting type
   * @param methodIdx Index in the method table
   * @param paramCount Expected parameter count
   * @param constructorsAccepted If false, constructors are excluded
   * @return The resolved member, or null if not found
   */
  static Member findByNameFallback(VmType vmType, long methodIdx, int paramCount,
                                   boolean constructorsAccepted)
  {
    // Get the method name from the starting type's method table
    String methodName = null;
    int nMethods = vmType.getNoDeclaredMethods();
    if (methodIdx >= 0 && methodIdx < nMethods)
      {
        VmMethod vmMethod = vmType.getDeclaredMethod((int) methodIdx);
        if (vmMethod != null)
          {
            methodName = vmMethod.getName();
          }
      }
    if (methodName == null)
      {
        return null;
      }

    List<VmType> types = collectHierarchy(vmType);

    // Search by name + param count in each type
    for (VmType searchType : types)
      {
        for (int i = 0; i < searchType.getNoDeclaredMethods(); i++)
          {
            VmMethod vmMethod = searchType.getDeclaredMethod(i);
            if (vmMethod != null && methodName.equals(vmMethod.getName()))
              {
                if (!constructorsAccepted && vmMethod.isConstructor())
                  {
                    continue;
                  }
                Member candidate = vmMethod.asMember();
                int pCount = memberParamCount(candidate);
                if (pCount == paramCount)
                  {
                    return candidate;
                  }
              }
          }
      }

    // Search interfaces by name + param count
    for (VmType searchType : types)
      {
        for (int i = 0; i < searchType.getNoInterfaces(); i++)
          {
            VmType iface = searchType.getInterface(i);
            if (iface != null)
              {
                for (int j = 0; j < iface.getNoDeclaredMethods(); j++)
                  {
                    VmMethod vmMethod = iface.getDeclaredMethod(j);
                    if (vmMethod != null && methodName.equals(vmMethod.getName()))
                      {
                        if (!constructorsAccepted && vmMethod.isConstructor())
                          {
                            continue;
                          }
                        Member candidate = vmMethod.asMember();
                        int pCount = memberParamCount(candidate);
                        if (pCount == paramCount)
                          {
                            return candidate;
                          }
                      }
                  }
              }
          }
      }

    return null;
  }

  /**
   * Convenience method for ClassTypeCommandSet: resolve with constructors accepted.
   */
  static Member resolveForClassType(VmType vmType, Class clazz,
                                    long methodIdx, int paramCount)
  {
    Member result = findByIndex(vmType, methodIdx, paramCount, true);
    if (result != null) return result;

    result = findByReflection(clazz, methodIdx, paramCount, true);
    if (result != null) return result;

    return findByNameFallback(vmType, methodIdx, paramCount, true);
  }

  /**
   * Convenience method for ObjectReferenceCommandSet: resolve without constructors.
   */
  static Member resolveForInstance(VmType vmType, Class clazz,
                                   long methodIdx, int paramCount)
  {
    Member result = findByIndex(vmType, methodIdx, paramCount, false);
    if (result != null) return result;

    result = findByReflection(clazz, methodIdx, paramCount, false);
    if (result != null) return result;

    return findByNameFallback(vmType, methodIdx, paramCount, false);
  }
}
