package comp.FieldAnalysisRequestHandlerPlugin;


import edu.uci.isr.myx.fw.AbstractMyxSimpleBrick;
import edu.uci.isr.myx.fw.IMyxName;
import edu.uci.isr.myx.fw.MyxUtils;

import edu.umkc.solr.core.PluginInfo;

import edu.umkc.type.tmpl.IFieldAnalysisRequestHandlerPlugin;

import java.util.Map;

public class FieldAnalysisRequestHandlerPluginArch extends AbstractMyxSimpleBrick implements IFieldAnalysisRequestHandlerPlugin
{
    public static final IMyxName msg_IFieldAnalysisRequestHandlerPlugin = MyxUtils.createName("edu.umkc.type.tmpl.IFieldAnalysisRequestHandlerPlugin");


	private IFieldAnalysisRequestHandlerPluginImp _imp;

    public FieldAnalysisRequestHandlerPluginArch (){
		_imp = getImplementation();
		if (_imp != null){
			_imp.setArch(this);
		} else {
			System.exit(1);
		}
	}
    
    protected IFieldAnalysisRequestHandlerPluginImp getImplementation(){
        try{
			return new FieldAnalysisRequestHandlerPluginImp();    
        } catch (Exception e){
            System.err.println(e.getMessage());
            return null;
        }
    }

    public void init(){
        _imp.init();
    }
    
    public void begin(){
        _imp.begin();
    }
    
    public void end(){
        _imp.end();
    }
    
    public void destroy(){
        _imp.destroy();
    }
    
	public Object getServiceObject(IMyxName arg0) {
		if (arg0.equals(msg_IFieldAnalysisRequestHandlerPlugin)){
			return this;
		}        
		return null;
	}
  
    //To be imported: Map,PluginInfo
    public boolean registerFieldAnalysisRequestHandlerPlugin (final PluginInfo info,final Map<String, PluginInfo> infoMap)   {
		return _imp.registerFieldAnalysisRequestHandlerPlugin(info,infoMap);
    }    
}