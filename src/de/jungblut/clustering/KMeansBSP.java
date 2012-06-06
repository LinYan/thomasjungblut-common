package de.jungblut.clustering;

import gnu.trove.map.hash.TIntObjectHashMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.util.ReflectionUtils;

import de.jungblut.bsp.CenterMessage;
import de.jungblut.clustering.model.ClusterCenter;
import de.jungblut.clustering.model.VectorWritable;
import de.jungblut.distance.DistanceMeasurer;
import de.jungblut.distance.EuclidianDistance;
import de.jungblut.math.DoubleVector;
import de.jungblut.math.dense.DenseDoubleVector;
import de.jungblut.visualize.GnuPlot;

public final class KMeansBSP extends
    BSP<VectorWritable, NullWritable, ClusterCenter, VectorWritable> {

  private static final Log LOG = LogFactory.getLog(KMeansBSP.class);
  private ClusterCenter[] centers;
  private List<DoubleVector> cache = new ArrayList<>();
  private int maxIterations;
  private DistanceMeasurer distanceMeasurer;

  @Override
  public final void setup(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException, InterruptedException {

    Path centroids = new Path(peer.getConfiguration().get("centroid.path"));
    FileSystem fs = FileSystem.get(peer.getConfiguration());
    final ArrayList<ClusterCenter> centers = new ArrayList<>();
    try (SequenceFile.Reader reader = new SequenceFile.Reader(fs, centroids,
        peer.getConfiguration())) {
      ClusterCenter key = new ClusterCenter();
      NullWritable value = NullWritable.get();
      while (reader.next(key, value)) {
        ClusterCenter center = new ClusterCenter(key);
        centers.add(center);
      }
    }

    if (centers.size() == 0) {
      throw new IllegalArgumentException(
          "Centers file must contain at least a single center!");
    } else {
      this.centers = centers.toArray(new ClusterCenter[centers.size()]);
    }

    distanceMeasurer = new EuclidianDistance();
    String distanceClass = peer.getConfiguration()
        .get("distance.measure.class");
    if (distanceClass != null) {
      try {
        distanceMeasurer = ReflectionUtils.newInstance(distanceClass);
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }
    }

    maxIterations = peer.getConfiguration()
        .getInt("k.means.max.iterations", -1);
  }

  @Override
  public final void bsp(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException, InterruptedException, SyncException {
    long converged;
    while (true) {
      assignCenters(peer);
      peer.sync();
      converged = updateCenters(peer);
      peer.reopenInput();
      if (converged == 0)
        break;
      if (maxIterations > 0 && maxIterations < peer.getSuperstepCount())
        break;
    }
    LOG.info("Finished! Writing the assignments...");
    recalculateAssignmentsAndWrite(peer);
  }

  private long updateCenters(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException {
    // this is the update step
    ClusterCenter[] msgCenters = new ClusterCenter[centers.length];
    CenterMessage msg;
    // basically just summing incoming vectors
    while ((msg = (CenterMessage) peer.getCurrentMessage()) != null) {
      ClusterCenter oldCenter = msgCenters[msg.getTag()];
      ClusterCenter newCenter = msg.getData();
      if (oldCenter == null) {
        msgCenters[msg.getTag()] = newCenter;
      } else {
        oldCenter.plus(newCenter);
      }
    }
    // divide by how often we globally summed vectors
    for (ClusterCenter c : msgCenters) {
      // and only if we really have an update for c
      if (c != null) {
        c.divideByK();
      }
    }
    // finally check for convergence
    long convergedCounter = 0L;
    for (int i = 0; i < msgCenters.length; i++) {
      final ClusterCenter oldCenter = centers[i];
      if (msgCenters[i] != null) {
        double calculateError = oldCenter.calculateError(msgCenters[i]
            .getCenterVector());
        if (calculateError > 0.0d) {
          centers[i] = msgCenters[i];
          convergedCounter++;
        }
      }
    }
    return convergedCounter;
  }

  private void assignCenters(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException {
    // each task has all the centers, if a center has been updated it
    // needs to be broadcasted.
    final ClusterCenter[] newCenterArray = new ClusterCenter[centers.length];
    // we have an assignment step
    if (cache.isEmpty()) {
      final NullWritable value = NullWritable.get();
      final VectorWritable key = new VectorWritable();
      while (peer.readNext(key, value)) {
        cache.add(key.getVector().deepCopy());
        final int lowestDistantCenter = getNearestCenter(key.getVector());
        final ClusterCenter clusterCenter = newCenterArray[lowestDistantCenter];
        if (clusterCenter == null) {
          newCenterArray[lowestDistantCenter] = new ClusterCenter(key);
        } else {
          // add the vector to the center
          newCenterArray[lowestDistantCenter].plus(key);
        }
      }
    } else {
      for (DoubleVector key : cache) {
        final int lowestDistantCenter = getNearestCenter(key);
        final ClusterCenter clusterCenter = newCenterArray[lowestDistantCenter];
        if (clusterCenter == null) {
          newCenterArray[lowestDistantCenter] = new ClusterCenter(key);
        } else {
          // add the vector to the center
          newCenterArray[lowestDistantCenter].plus(key);
        }
      }
    }
    for (int i = 0; i < newCenterArray.length; i++) {
      if (newCenterArray[i] != null) {
        for (String peerName : peer.getAllPeerNames()) {
          // we need a new instance here because otherwise the same instance
          // will be shared among different threads creating strange results.
          peer.send(peerName, new CenterMessage(i, new ClusterCenter(
              newCenterArray[i])));
        }
      }
    }
  }

  private int getNearestCenter(DoubleVector key) {
    int lowestDistantCenter = 0;
    double lowestDistance = Double.MAX_VALUE;
    for (int i = 0; i < centers.length; i++) {
      double estimatedDistance = distanceMeasurer.measureDistance(
          centers[i].getCenterVector(), key);
      // check if we have a can assign a new center, because we
      // got a lower distance
      if (estimatedDistance < lowestDistance) {
        lowestDistance = estimatedDistance;
        lowestDistantCenter = i;
      }
    }
    return lowestDistantCenter;
  }

  private void recalculateAssignmentsAndWrite(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException {
    final NullWritable value = NullWritable.get();
    final VectorWritable key = new VectorWritable();
    while (peer.readNext(key, value)) {
      final int lowestDistantCenter = getNearestCenter(key.getVector());
      centers[lowestDistantCenter].clusterIndex = lowestDistantCenter;
      peer.write(centers[lowestDistantCenter], key);
    }
    // writeFinalCenters(peer);
  }

  @SuppressWarnings("unused")
  private void writeFinalCenters(
      BSPPeer<VectorWritable, NullWritable, ClusterCenter, VectorWritable> peer)
      throws IOException {
    // just write centers, for performance improvement and just on master
    // task
    if (peer.getPeerName().equals(peer.getPeerName(0))) {
      final VectorWritable val = new VectorWritable(0);
      for (ClusterCenter center : centers) {
        peer.write(center, val);
      }
    }
  }

  public static BSPJob createJob(Configuration cnf, Path in, Path out)
      throws IOException {
    HamaConfiguration conf = new HamaConfiguration(cnf);
    BSPJob job = new BSPJob(conf, KMeansBSP.class);
    job.setJobName("KMeans Clustering");
    job.setJarByClass(KMeansBSP.class);
    job.setBspClass(KMeansBSP.class);
    job.setInputPath(in);
    job.setOutputPath(out);
    job.setInputFormat(org.apache.hama.bsp.SequenceFileInputFormat.class);
    job.setOutputFormat(org.apache.hama.bsp.SequenceFileOutputFormat.class);
    job.setOutputKeyClass(ClusterCenter.class);
    job.setOutputValueClass(VectorWritable.class);
    return job;
  }

  public static void main(String[] args) throws IOException,
      ClassNotFoundException, InterruptedException {

    if (args.length < 4) {
      LOG.info("USAGE: <COUNT> <K> <DIMENSION OF VECTORS> <MAXITERATIONS> <optional: num of tasks>");
      return;
    }

    Path in = new Path("files/clustering/in/data.seq");
    Path center = new Path("files/clustering/in/center/cen.seq");
    Path out = new Path("files/clustering/out");

    Configuration conf = new Configuration();
    conf.set("centroid.path", center.toString());
    conf.set("bsp.local.tasks.maximum", "2");
    BSPJob job = createJob(conf, in, out);

    // count = 7000000 spawns arround 6 tasks for 32mb block size
    int count = Integer.parseInt(args[0]);
    int k = Integer.parseInt(args[1]);
    int dimension = Integer.parseInt(args[2]);

    LOG.info("N: " + count + " k: " + k + " Dimension: " + dimension
        + " Iterations: " + args[3]);
    conf.setInt("k.means.max.iterations", Integer.parseInt(args[3]));

    FileSystem fs = FileSystem.get(conf);
    // prepare the input, like deleting old versions and creating centers
    prepareInput(count, k, dimension, conf, in, center, out, fs);
    if (args.length == 5) {
      job.setNumBspTask(Integer.parseInt(args[4]));
    }

    // just submit the job
    job.waitForCompletion(true);

    // reads the output
    readOutput(conf, out, fs);
  }

  private static void readOutput(Configuration conf, Path out, FileSystem fs)
      throws IOException {
    FileStatus[] stati = fs.listStatus(out);
    HashMap<DoubleVector, Integer> centerMap = new HashMap<>();
    TIntObjectHashMap<List<DoubleVector>> map = new TIntObjectHashMap<>();
    int clusterIds = 0;
    for (FileStatus status : stati) {
      if (!status.isDir()) {
        Path path = status.getPath();
        LOG.debug("FOUND " + path.toString());
        SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, conf);
        ClusterCenter key = new ClusterCenter();
        VectorWritable v = new VectorWritable();
        while (reader.next(key, v)) {
          DenseDoubleVector centerVector = new DenseDoubleVector(key
              .getCenterVector().toArray());
          Integer integer = centerMap.get(centerVector);
          if (integer == null) {
            integer = clusterIds++;
            centerMap.put(centerVector, integer);
          }
          List<DoubleVector> list = map.get(integer.intValue());
          if (list == null) {
            list = new ArrayList<DoubleVector>();
            map.put(integer.intValue(), list);
          }
          list.add(new DenseDoubleVector(v.getVector().deepCopy().toArray()));
        }
        reader.close();
      }
    }
    int centerId = clusterIds++;
    map.put(centerId,
        Arrays.asList(centerMap.keySet().toArray(new DoubleVector[0])));
    // GnuPlot.GNUPLOT_PATH = "\"C:/Program Files (x86)/gnuplot/bin/gnuplot\"";
    // GnuPlot.TMP_PATH = "C:/tmp/gnuplot/";
    GnuPlot.drawPoints(map);
  }

  private static void prepareInput(int count, int k, int dimension,
      Configuration conf, Path in, Path center, Path out, FileSystem fs)
      throws IOException {
    if (fs.exists(out))
      fs.delete(out, true);

    if (fs.exists(center))
      fs.delete(out, true);

    if (fs.exists(in))
      fs.delete(in, true);

    final SequenceFile.Writer centerWriter = SequenceFile.createWriter(fs,
        conf, center, ClusterCenter.class, NullWritable.class,
        CompressionType.NONE);
    final NullWritable value = NullWritable.get();

    final SequenceFile.Writer dataWriter = SequenceFile.createWriter(fs, conf,
        in, VectorWritable.class, NullWritable.class, CompressionType.NONE);

    Random r = new Random();
    for (int i = 0; i < count; i++) {

      double[] arr = new double[dimension];
      for (int d = 0; d < dimension; d++) {
        arr[d] = r.nextInt(count);
      }
      VectorWritable vector = new VectorWritable(arr);
      dataWriter.append(vector, value);
      if (k > i) {
        centerWriter.append(new ClusterCenter(vector), value);
      } else if (k == i) {
        centerWriter.close();
      }
    }
    dataWriter.close();
  }
}
