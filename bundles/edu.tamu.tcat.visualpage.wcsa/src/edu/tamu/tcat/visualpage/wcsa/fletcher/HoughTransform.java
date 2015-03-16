package edu.tamu.tcat.visualpage.wcsa.fletcher;

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import edu.tamu.tcat.analytics.image.region.Point;

public class HoughTransform<T>
   {
      private Table<Integer, Integer, HoughAccumulator<T>> accumulator;
      private final Function<T, Point> fn;
      private final double[] angles;
      private final double radialResolution;
//      private SortedSet<Integer> rhoValues;
//      private SortedSet<Integer> thetaValues;
      
      /**
       * 
       * @param angularResolution 
       * @param radialResolution
       */
      public HoughTransform(Function<T, Point> fn, double angularResolution, double radialResolution)
      {
         
         this.fn = fn;
         accumulator = HashBasedTable.create();
         this.radialResolution = radialResolution;
         
         int ix = 0;
         int numAngles = (int)Math.ceil(Math.PI / angularResolution);
         angles = new double[numAngles];
         for (double theta = 0; theta < Math.PI; theta += angularResolution)
         {
            angles[ix++] = theta;
         }
      }

      public Object getByAngle(HoughAccumulator<T> acc)
      {
         throw new UnsupportedOperationException();
         // TODO return something more semantic
//         return new ObservationsByAngle(accumulator.column(acc.getAngleIndex()));
      }

      /**
       * 
       * @return accumulators uses to count values for individual rho, theta pairs
       *       within the resolution of this transform.
       */
      public Collection<HoughAccumulator<T>> getAccumulators()
      {
         return accumulator.cellSet().stream()
                     .map(cell -> cell.getValue())
                     .collect(Collectors.toList());
      }

      /**
       * 
       * @param fn
       * @param angles Must not be modified by caller after creation.
       * @param radialResolution
       */
      public HoughTransform(Function<T, Point> fn, double[] angles, double radialResolution)
      {
         
         this.fn = fn;
         this.angles = angles;
         this.accumulator = HashBasedTable.create();
         this.radialResolution = radialResolution;
      }
      
      public void addObservation(T observation)
      {
//         rhoValues = null;
//         thetaValues = null;
         
         Point p = fn.apply(observation);
         int x = p.getX();
         int y = p.getY();

         IntStream.range(0, angles.length).forEach(i -> {
            double theta = angles[i];
            double rho = x * Math.cos(theta) + y * Math.sin(theta);
            Integer rhoIx = Integer.valueOf((int)Math.floor(rho / radialResolution));
            
            HoughAccumulator<T> acc = accumulator.get(rhoIx, i);
            if (acc == null)
            {
               acc = new HoughAccumulator<T>(rhoIx, i);
               accumulator.put(rhoIx, i, acc);
            }
            
            acc.observations.add(observation);
         });
      }
      
      public HoughAccumulator<T> getCell(HoughPoint p)
      {
         double rho = p.rho;
         double theta = p.theta;
         
         double minDiff = Double.MAX_VALUE;
         int thetaIx = -1;
         for (int i = 0; i < angles.length; i++)
         {
            double diff = Math.abs(theta - angles[i]);
            if (diff < minDiff)
            {
               minDiff = diff;
               thetaIx = i;
            }
         }
         
         int rhoIx = Integer.valueOf((int)Math.floor(rho / radialResolution));
         return accumulator.get(Integer.valueOf(rhoIx), Integer.valueOf(thetaIx));
      }
      
      public HoughPoint getReferencePoint(HoughAccumulator<T> cell)
      {
         double rho = cell.getRhoIndex() * radialResolution;
         double theta = angles[cell.getAngleIndex()];
         
         return new HoughPoint(rho, theta);
      }
//      
//      public void pack() {
//         rhoValues = new TreeSet<>(accumulator.rowKeySet());
//         thetaValues = new TreeSet<>(accumulator.columnKeySet());
//      }
   }