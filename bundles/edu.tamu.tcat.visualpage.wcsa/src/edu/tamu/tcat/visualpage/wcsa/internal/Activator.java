package edu.tamu.tcat.visualpage.wcsa.internal;

import edu.tamu.tcat.osgi.services.util.ActivatorBase;

public class Activator extends ActivatorBase 
{

   private static Activator instance;

   public Activator()
   {
      instance = this;
   }

   public static Activator getDefault()
   {
      return instance;
   }
}
