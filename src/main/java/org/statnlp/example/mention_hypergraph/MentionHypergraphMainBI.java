//获取statnlp框架的最新的信息，可以联系allanmcgrady@gmail.com
package org.statnlp.example.mention_hypergraph;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.statnlp.commons.ml.opt.OptimizerFactory;
import org.statnlp.commons.ml.opt.GradientDescentOptimizer; 
import org.statnlp.commons.types.Instance;
import org.statnlp.example.mention_hypergraph.MentionHypergraphInstance.WordsAndTags;
import org.statnlp.example.mention_hypergraph.MentionHypergraphFeatureManager.FeatureType;
import org.statnlp.hypergraph.DiscriminativeNetworkModel;
import org.statnlp.hypergraph.GlobalNetworkParam;
import org.statnlp.hypergraph.NetworkConfig;
import org.statnlp.hypergraph.NetworkModel;
import org.statnlp.hypergraph.decoding.Metric;
import org.statnlp.hypergraph.neural.BidirectionalLSTM;
import org.statnlp.hypergraph.neural.GlobalNeuralNetworkParam;
import org.statnlp.hypergraph.neural.NeuralNetworkCore;

 

public class MentionHypergraphMainBI {
	
	public static ArrayList<Label> labels;
	public static String neuralType = "";//-----------------------------------------------
	public static int gpuId = 0;//---------------------------------------------------
	public static String embedding = "glove";
	public static String neural_config = "nn-crf-interface/neural_server/neural.config";
	public static double testR, testP, testF, devR, devF, devP, finalmp = -1;
	public static double testR2, testP2, testF2, devR2, devF2, devP2, finalmp2 = -1;
	public static String nnOptimizer = "lbfgs";
	public static double lr = 0.01;
	public static OptimizerFactory optimizer =  OptimizerFactory.getLBFGSFactory();
	public static boolean evalOnDev = false	;


	public static void main(String[] args) throws Exception{
		
		//Config设置
		GradientDescentOptimizer.decaystep = 90000;  //if do not want to decay, set a very large number.
		boolean serializeModel = false; 
		System.out.println("NACCL model");
		boolean readModelIfAvailable = false;  
		NetworkConfig.USE_BATCH_TRAINING = false;
        NetworkConfig.NUM_THREADS = 20; // 
        NetworkConfig.PRINT_BATCH_OBJECTIVE=true;
		int numIterations = 10000;//Integer.valueOf(args[0]);  
		neuralType = "lstm";//args[1];
        String dataset = "ACE04";//args[2];
		NetworkConfig.USE_NEURAL_FEATURES = true;//Boolean.valueOf(args[3]); 
		double mh_value = 2;//Double.valueOf(args[4]);	
		NetworkConfig.REGULARIZE_NEURAL_FEATURES = true;
     	NetworkConfig.OPTIMIZE_NEURAL = true;//!!!!!!!!!!!!!!!!!!!!!1
        NetworkConfig.INIT_FV_WEIGHTS = false;
        String modelPath = "kk.model";
		NetworkConfig.L2_REGULARIZATION_CONSTANT = 0.01;
		String train_filename = "data/data/"+dataset+"/tt.data"; 
		String dev = "data/data/"+dataset+"/tt.data";
		NetworkConfig.RANDOM_BATCH = false;
		NetworkConfig.OS="linux";
		Embedding e = new Embedding("glove.6B.50d.txt");
		NetworkConfig.TRAIN_MODE_IS_GENERATIVE = false;
		NetworkConfig.CACHE_FEATURES_DURING_TRAINING = true;
		NetworkConfig.OBJTOL = 1e-4;
		NetworkConfig.PARALLEL_FEATURE_EXTRACTION = true; 
		
		
		//数据读取
     	MentionHypergraphInstance[] trainInstances = readData(train_filename, true, true);
		MentionHypergraphInstance[] devInstances = readData(dev, true, false);
		System.out.println(Label.LABELS);
		labels = new ArrayList<Label>();
		labels.addAll(Label.LABELS.values()); 
		int maxSize = 0;
		for(MentionHypergraphInstance instance: trainInstances){
			maxSize = Math.max(maxSize, instance.size());
		}
		


		//设置神经网络架构
        System.out.println(Label.LABELS.size());
		List<NeuralNetworkCore> nets = new ArrayList<NeuralNetworkCore>();
		if(NetworkConfig.USE_NEURAL_FEATURES){
			if (neuralType.equals("lstm")) {
				int hiddenSize = 50;
				String optimizer = nnOptimizer;
				boolean bidirection = true;
				nets.add(new BidirectionalLSTM(hiddenSize, bidirection, optimizer, lr, 5, Label.LABELS.size(), gpuId, embedding));
			} else if (neuralType.equals("continuous")) {
				nets.add(new ECRFContinuousFeatureValueProvider(50, Label.LABELS.size()));
			} else if (neuralType.equals("mlp")) {
				nets.add(new ECRFMLP(Label.LABELS.size()));
			}
		} 
		GlobalNetworkParam gnp = new GlobalNetworkParam(optimizer, new GlobalNeuralNetworkParam(nets));
		
		
		
		int size = trainInstances.length;
		
		System.err.println("Read.."+size+" instances.");
	
		//构建网络
		MentionHypergraphFeatureManager fm = new MentionHypergraphFeatureManager(gnp);
		MentionHypergraphNetworkCompiler compiler = new MentionHypergraphNetworkCompiler(labels.toArray(new Label[labels.size()]), maxSize);
		NetworkModel model = DiscriminativeNetworkModel.create(fm, compiler);	
		String tmpOut = "data/tmp_out.txt";	
		/*
		Function<Instance[], Metric> evalFunc = new Function<Instance[], Metric>() {
				@Override
				public Metric apply(Instance[] t) {
					return ECRFEval.evalNER(t, tmpOut);
				}
				
			};
        */
	    //模型训练
		model.train(trainInstances, numIterations);

		System.out.println("\n\n");
		
		
		//模型训练完成后解码预测
		String out = "\n\noriginal model is\n";
		for(int t = 0; t < 2; t ++)//--------------------------------------------------------------------------------------------------------------------------------------------------------
		{
		
		String test_filename = "data/data/"+dataset+"/tt.data";
		if(t== 1)
			test_filename = "data/data/"+dataset+"/tt.data"; 
		MentionHypergraphInstance[] testInstances = readData(test_filename, true, false);
		for(MentionHypergraphInstance instance: testInstances){
			maxSize = Math.max(maxSize, instance.size());
		}
		Instance[] predictions = model.decode(testInstances);
		//ECRFEval.evalNER(predictions, "");
		fm.getParam_G().setVersion(fm.getParam_G().getVersion()+1);
		int corr = 0;
		int totalGold = 0;
		int totalPred = 0;
		//write part
		{
			String outfile = "data/data/"+dataset+"/BILU-dev-out.data";
			if(t== 1)
				outfile = "data/data/"+dataset+"/BILU-test-out.data"; 
			FileOutputStream fout = new FileOutputStream(outfile);
			for(Instance inst: predictions){
				MentionHypergraphInstance instance = (MentionHypergraphInstance)inst;
				List<Span> goldSpans = instance.output;
				List<Span> predSpans = instance.prediction;
				String strgold = "";
				for(int m = 0; m < goldSpans.size(); m++) {
					strgold += goldSpans.get(m).start+","+goldSpans.get(m).end+","+goldSpans.get(m).label.id+" ";
				}
				strgold = strgold.trim();
				String strpre = "";
				for(int m = 0; m < predSpans.size(); m++) {
					strpre += predSpans.get(m).start+","+predSpans.get(m).end+","+predSpans.get(m).label.id+" ";
				}
				strpre = strpre.trim();
				fout.write((strgold+"\n").getBytes());
				fout.write((strpre+"\n").getBytes());
				fout.write("\n".getBytes());			
			}
			fout.close();
		}	
		for(Instance inst: predictions){
			MentionHypergraphInstance instance = (MentionHypergraphInstance)inst;
			List<Span> goldSpans = instance.output;
			List<Span> predSpans = instance.prediction;
			int curTotalGold = goldSpans.size();
			totalGold += curTotalGold;
			int curTotalPred = predSpans.size();
			totalPred += curTotalPred;
			int curCorr = countOverlaps(goldSpans, predSpans);
			corr += curCorr;
			if(curTotalPred == 0) curTotalPred = 1;
			if(curTotalGold == 0) curTotalGold = 1;
		}
		if(totalPred == 0) totalPred = 1;
		if(totalGold == 0) totalGold = 1;
		double precision = 100.0*corr/totalPred;
		double recall = 100.0*corr/totalGold;
		double f1 = 2/((1/precision)+(1/recall));
		if(totalPred == 0) precision = 0.0;
		if(totalGold == 0) recall = 0.0;
		if(totalPred == 0 || totalGold == 0) f1 = 0.0;
		if(t==0) out += "Dev set:   ";
		else if(t== 1) out+="Test set:   "; 
		out += String.format("P: %.2f%%    ", precision);
		out += String.format("R: %.2f%%    ", recall);
		out += String.format("F: %.2f%%\n", f1);
		}
		System.out.println(out);
	
		
		int mentionPenaltyFeatureIndex = fm.getParam_G().getFeatureId(FeatureType.MENTION_PENALTY.name(), "MP", "MP");
		boolean write = false;
		boolean equal = false;
		for(double mentionPenalty = mh_value; mentionPenalty <= mh_value; mentionPenalty += 0.5){
			System.out.println(String.format("\nMention penalty: %.2f", mentionPenalty));
			for(int t = 0; t < 2; t ++)
			{
			String test_filename = "data/data/"+dataset+"/tt.data";
			if(t== 1)
				test_filename = "data/data/"+dataset+"/tt.data";
			MentionHypergraphInstance[] testInstances = readData(test_filename, true, false);
			for(MentionHypergraphInstance instance: testInstances){
				maxSize = Math.max(maxSize, instance.size());
			}
			fm.getParam_G().setWeight(mentionPenaltyFeatureIndex, mentionPenalty);
			Instance[] predictions = model.decode(testInstances);
			fm.getParam_G().setVersion(fm.getParam_G().getVersion()+1);
			//write part
			{
				String outfile = "data/data/"+dataset+"/BILU-dev-out-fopt.data";
				if(t== 1)
					outfile = "data/data/"+dataset+"/BILU-test-out-fopt.data"; 
				FileOutputStream fout = new FileOutputStream(outfile);
				for(Instance inst: predictions){
					MentionHypergraphInstance instance = (MentionHypergraphInstance)inst;
					List<Span> goldSpans = instance.output;
					List<Span> predSpans = instance.prediction;
					String strgold = "";
					for(int m = 0; m < goldSpans.size(); m++) {
						strgold += goldSpans.get(m).start+","+goldSpans.get(m).end+","+goldSpans.get(m).label.id+" ";
					}
					strgold = strgold.trim();
					String strpre = "";
					for(int m = 0; m < predSpans.size(); m++) {
						strpre += predSpans.get(m).start+","+predSpans.get(m).end+","+predSpans.get(m).label.id+" ";
					}
					strpre = strpre.trim();
					fout.write((strgold+"\n").getBytes());
					fout.write((strpre+"\n").getBytes());
					fout.write("\n".getBytes());			
				}
				fout.close();
			}
					
			int corr = 0;
			int totalGold = 0;
			int totalPred = 0;
			for(Instance inst: predictions){
				MentionHypergraphInstance instance = (MentionHypergraphInstance)inst;
				List<Span> goldSpans = instance.output;
				List<Span> predSpans = instance.prediction;
				int curTotalGold = goldSpans.size();
				totalGold += curTotalGold;
				int curTotalPred = predSpans.size();
				totalPred += curTotalPred;
				int curCorr = countOverlaps(goldSpans, predSpans);
				corr += curCorr;
			}
			double precision = 100.0*corr/totalPred;
			double recall = 100.0*corr/totalGold;
			double f1 = 2/((1/precision)+(1/recall));
			if(totalPred == 0) precision = 0.0;
			if(totalGold == 0) recall = 0.0;
			if(totalPred == 0 || totalGold == 0) f1 = 0.0;
			if(t==0) System.out.print("Dev set:   ");
			else if(t== 1)System.out.print("Test set:   "); 
			System.out.print(String.format("P: %.2f%%    ", precision));
			System.out.print(String.format("R: %.2f%%    ", recall));
			System.out.println(String.format("F: %.2f%%", f1));
			
			if(t == 0) {
				if(f1 > devF) {
					devF = f1;
					devR = recall;
					devP = precision;
					finalmp = mentionPenalty;
					write = true;
				} else if(f1 == devF) {
					equal = true;
					devF2 = f1;
					devR2 = recall;
					devP2 = precision;
					finalmp2 = mentionPenalty;
					write = true;
				} 
			} else if(t == 1 && write == true && equal) {
				if(f1 > testF)
				{
					testF = f1;
				    testR = recall;
				    testP = precision;
				    devF = devF2;
				    devR = devR2;
				    devP = devP2;
				}
				write = false;
				equal = false;
			} else if(t == 1 && write == true) {
				testF = f1;
				testR = recall;
				testP = precision;
				write = false;
			}
			//System.out.print(String.format("P: %.2f%%    ", precision));
			//System.out.print(String.format("R: %.2f%%    ", recall));
			//System.out.println(String.format("F: %.2f%%", f1));
		   } 
		}
		System.out.println("-------------result---------------");
		System.out.println(out);
		System.out.println("finale mp: "+finalmp);
		System.out.print("Dev set:");
		System.out.print(String.format("P: %.2f%%    ", devP));
		System.out.print(String.format("R: %.2f%%    ", devR));
		System.out.println(String.format("F: %.2f%%", devF));
		System.out.print("Test set:");
		System.out.print(String.format("P: %.2f%%    ", testP));
		System.out.print(String.format("R: %.2f%%    ", testR));
		System.out.println(String.format("F: %.2f%%", testF));
		
		
	}
	
	private static int countOverlaps(List<Span> list1, List<Span> list2){
		int result = 0;
		List<Span> copy = new ArrayList<Span>();
		copy.addAll(list2);
		for(Span span: list1){
			if(copy.contains(span)){
				copy.remove(span);
				result += 1;
			}
		}
		return result;
	}
	
	private static MentionHypergraphInstance[] readData(String fileName, boolean withLabels, boolean isLabeled) throws IOException{
		InputStreamReader isr = new InputStreamReader(new FileInputStream(fileName), "UTF-8");
		BufferedReader br = new BufferedReader(isr);
		ArrayList<MentionHypergraphInstance> result = new ArrayList<MentionHypergraphInstance>();
		int instanceId = 1;
		while(br.ready()){
			String words = br.readLine();
			MentionHypergraphInstance instance = new MentionHypergraphInstance(instanceId++, 1.0);
//			instance.words = markWords(words.trim().split(" "));
			String posTags = br.readLine();
//			instance.posTags = posTags.trim().split(" ");
			instance.input = new WordsAndTags(markWords(words.trim().split(" ")), posTags.trim().split(" "));
			String[] spans = br.readLine().split("\\|");
			if(spans.length == 1 && spans[0].length() == 0){
				spans = new String[0];
			}
			List<Span> output = new ArrayList<Span>();
			for(String span: spans){
				String[] tokens = span.split(" ");
				String[] indices = tokens[0].split(",");
				int[] intIndices = new int[indices.length];
				for(int i=0; i<4; i++){
					intIndices[i] = Integer.parseInt(indices[i]);
				}
				Label label = Label.get(tokens[1]);
				output.add(new Span(intIndices[0], intIndices[1], intIndices[2], intIndices[3], label));
			}
			instance.setOutput(output);
			if(isLabeled){
				instance.setLabeled();
			} else {
				instance.setUnlabeled();
			}
			br.readLine();
			result.add(instance);
		}
		br.close();
		return result.toArray(new MentionHypergraphInstance[result.size()]);
	}
	
	private static AttributedWord[] markWords(String[] words){
		AttributedWord[] result = new AttributedWord[words.length];
		for(int i=0; i<result.length; i++){
			result[i] = new AttributedWord(words[i]);
		}
		return result;
	}
}