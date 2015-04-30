/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.chombo.mr;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.LongRunningStats;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

/**
 * Calculates running average and other stats of some quantity
 * @author pranab
 *
 */
public class RunningAggregator  extends Configured implements Tool {

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Running aggregates  for numerical attributes";
        job.setJobName(jobName);
        
        job.setJarByClass(RunningAggregator.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "chombo");
        job.setMapperClass(RunningAggregator.AggrMapper.class);
        job.setReducerClass(RunningAggregator.AggrReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        int numReducer = job.getConfiguration().getInt("rug.num.reducer", -1);
        numReducer = -1 == numReducer ? job.getConfiguration().getInt("num.reducer", 1) : numReducer;
        job.setNumReduceTasks(numReducer);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}
	
	/**
	 * @author pranab
	 *
	 */
	public static class AggrMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
		private Tuple outKey = new Tuple();
		private Tuple outVal = new Tuple();
        private String fieldDelimRegex;
        private String[] items;
        private int[] quantityAttrOrdinals;
        private boolean isAggrFileSplit;
        private int[] idFieldOrdinals;
        private long newValue;
        private int statOrd;
        private static final int PER_FIELD_STAT_VAR_COUNT = 6;
        
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = config.get("field.delim.regex", ",");
        	quantityAttrOrdinals = Utility.intArrayFromString(config.get("quantity.attr.ordinals"));
        	
        	String aggrFilePrefix = config.get("aggregate.file.prefix", "");
        	if (!aggrFilePrefix.isEmpty()) {
        		isAggrFileSplit = ((FileSplit)context.getInputSplit()).getPath().getName().startsWith(aggrFilePrefix);
        	} else {
            	String incrFilePrefix = config.get("incremental.file.prefix", "");
            	if (!incrFilePrefix.isEmpty()) {
            		isAggrFileSplit = !((FileSplit)context.getInputSplit()).getPath().getName().startsWith(incrFilePrefix);
            	} else {
            		throw new IOException("Aggregate or incremental file prefix needs to be specified");
            	}
        	}
        	
        	if (null != config.get("id.field.ordinals")) {
        		idFieldOrdinals = Utility.intArrayFromString(config.get("id.field.ordinals"));
        	}
       }
 
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
            items  =  value.toString().split(fieldDelimRegex);
        	outKey.initialize();
        	outVal.initialize();
        	int initValue = 0;
        	if (null != idFieldOrdinals) {
      			for (int ord : idFieldOrdinals ) {
      				outKey.append(items[ord]);
      			}
        	} else {
        		//all fields before quantity are ID fields
	            for (int i = 0; i < quantityAttrOrdinals[0];  ++i) {
	            	outKey.append(items[i]);
	            }
        	}
        	
        	if (isAggrFileSplit) {
    			statOrd = idFieldOrdinals.length;
    			for ( int ord : quantityAttrOrdinals) {
        			//existing aggregation - quantity attrubute ordinal, count, sum, sum of squares
                    outVal.add(Integer.parseInt(items[statOrd]), Long.parseLong(items[statOrd+1]) ,  
                    		Long.parseLong(items[statOrd + 2]),  Long.parseLong(items[statOrd + 3]));
                    statOrd += PER_FIELD_STAT_VAR_COUNT;
    			}
        	} else {
        		//incremental - first run will have only incremental file
    			for ( int ord : quantityAttrOrdinals) {
	        		newValue = Long.parseLong( items[ord]);
	                outVal.add(ord, (long)1, newValue, newValue * newValue);
    			}
        	}
        	context.write(outKey, outVal);
        }
 	}	

	   /**
  * @author pranab
  *
  */
 public static class AggrReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
 		private Text outVal = new Text();
 		private StringBuilder stBld =  new StringBuilder();;
 		private  String fieldDelim;
 		private int ord;
		private long sum;
		private long count;
		private long sumSq;
        private int[] quantityAttrOrdinals;
        private int index;
        private Map<Integer, LongRunningStats> runningStats = new HashMap<Integer, LongRunningStats>();
		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelim = config.get("field.delim.out", ",");
        	quantityAttrOrdinals = Utility.intArrayFromString(config.get("quantity.attr.ordinals"));
       }
		
    	/* (non-Javadoc)
    	 * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
    	 */
    	protected void reduce(Tuple key, Iterable<Tuple> values, Context context)
        	throws IOException, InterruptedException {
    		sum = 0;
    		sumSq = 0;
    		count = 0;
    		for (Tuple val : values) {
    			index = 0;
    			for ( int quantOrd : quantityAttrOrdinals) {
    				ord = val.getInt(index);
    				count = val.getLong(++index);
    				sum  = val.getLong(++index);
    				sumSq  = val.getLong(++index);
    				
    				LongRunningStats stat = runningStats.get(ord);
    				if (null == stat) {
    					runningStats.put(ord, new LongRunningStats(ord, count, sum, sumSq));
    				} else {
    					stat.accumulate(count, sum, sumSq);
    				}
    			}
    		}   	
    		
    		stBld.delete(0, stBld.length());
    		stBld.append(key.toString()).append(fieldDelim);
    		
    		//all quant field
			for ( int quantOrd : quantityAttrOrdinals) {
				LongRunningStats stat = runningStats.get(quantOrd);
				if (stat.getField() != quantOrd) {
					throw new IllegalStateException("field ordinal does not match");
				}
				stat.process();
				stBld.append(stat.getField()).append(fieldDelim).append(stat.getCount()).append(fieldDelim).
				  	append(stat.getSum()).append(fieldDelim).append(stat.getSumSq()).append(fieldDelim).
				  	append(stat.getAvg()).append(fieldDelim).append(stat.getStdDev());
			}
    		
        	outVal.set(stBld.toString());
			context.write(NullWritable.get(), outVal);
    	}
		
 	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
     int exitCode = ToolRunner.run(new RunningAggregator(), args);
     System.exit(exitCode);
	}

 
}
