package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.util.ArrayList;
import java.util.Collection;

// intended for internal use
public class HoughAccumulator<T>
{
   private final int angleIx;
   private final int rhoIx;
   Collection<T> observations = new ArrayList<T>();
   
   public HoughAccumulator(Integer rhoIx, Integer angleIx)
   {
      this.rhoIx = rhoIx.intValue();
      this.angleIx = angleIx.intValue();
   }
   
   public int size()
   {
      return observations.size();
   }

   public int getRhoIndex()
   {
      return rhoIx;
   }
   
   public int getAngleIndex()
   {
      return this.angleIx;
   }
}