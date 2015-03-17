package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// intended for internal use
public class HoughAccumulator<T>
{
   private final int angleIx;
   private final int rhoIx;
   private List<T> observations = new ArrayList<T>();
   
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
   
   public void add(T observation)
   {
      observations.add(observation);
   }
   
   public Collection<T> getObservations()
   {
      return Collections.unmodifiableCollection(observations);
   }
}