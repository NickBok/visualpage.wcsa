package edu.tamu.tcat.visualpage.wcsa.docstrum;

import java.awt.Graphics;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoint;

import edu.tamu.tcat.analytics.image.region.BoundingBox;
import edu.tamu.tcat.analytics.image.region.Point;
import edu.tamu.tcat.analytics.image.region.SimpleBoundingBox;
import edu.tamu.tcat.dia.segmentation.cc.ConnectedComponent;
import edu.tamu.tcat.visualpage.wcsa.Polynomial;

class Line
{
   // HACK need to provide builder or something similar, or else build set of CC elsewhere and pass in.
   int sequence = -1;
   int setId = -1;
   Set<ConnectedComponent> components = new HashSet<>();
   
   private Polynomial fitline;
   private BoundingBox centroidBounds;
   private BoundingBox bounds;
   
   public void init()
   {
      PolynomialCurveFitter fitter = PolynomialCurveFitter.create(1);
      List<WeightedObservedPoint> points = components.stream().map(cc -> { 
         Point centroid = cc.getCentroid();
         return new WeightedObservedPoint(1, centroid.getX(), centroid.getY());
      })
      .collect(Collectors.toList());
      
      // compute centroid bounding box and line bounding box
      int[] lineBounds = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
      int[] centroidAcc = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
      for (ConnectedComponent cc : components)
      {
         BoundingBox box = cc.getBounds();
         lineBounds[0] = Math.min(lineBounds[0], box.getLeft());
         lineBounds[1] = Math.min(lineBounds[1], box.getTop());
         lineBounds[2] = Math.max(lineBounds[2], box.getRight());
         lineBounds[3] = Math.max(lineBounds[3], box.getBottom());
         
         Point centroid = cc.getCentroid();
         centroidAcc[0] = Math.min(centroidAcc[0], centroid.getX());
         centroidAcc[1] = Math.min(centroidAcc[1], centroid.getY());
         centroidAcc[2] = Math.max(centroidAcc[2], centroid.getX());
         centroidAcc[3] = Math.max(centroidAcc[3], centroid.getY());
      }
      
      bounds = new SimpleBoundingBox(lineBounds[0], lineBounds[1], lineBounds[2], lineBounds[3]);
      centroidBounds = new SimpleBoundingBox(centroidAcc[0], centroidAcc[1], centroidAcc[2], centroidAcc[3]);
      
      fitline = new Polynomial(fitter.fit(points));
   }
   
   public void drawCenterLine(Graphics g)
   {
      int xMin = centroidBounds.getLeft();
      int yMin = (int)Math.round(fitline.applyAsDouble(xMin));
      int xMax = centroidBounds.getRight();
      int yMax = (int)Math.round(fitline.applyAsDouble(xMax));
      g.drawLine(xMin, yMin, xMax, yMax);
   }
   
   public void drawBox(Graphics g)
   {
      int xMin = bounds.getLeft();
      int yMin = bounds.getTop();
      int xMax = bounds.getRight();
      int yMax = bounds.getBottom();
      g.drawRect(bounds.getLeft(), bounds.getTop(), bounds.getWidth(), bounds.getHeight());
   }
}