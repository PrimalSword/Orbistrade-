package com.orbiswatchlab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivityV17Lab extends Activity {
    private static final String VERSION="0.17";
    private static final int REQ=1700, MAX=300;
    private static final long SCAN_MS=15000;
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID RX=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID TX=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
    private static final byte[] TEST={(byte)0xDF,0,6,1,5,0x10,6,0,1,0};
    private final Handler h=new Handler(Looper.getMainLooper());
    private final SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US);
    private final SimpleDateFormat fileTime=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US);
    private final ArrayList<Capture> captures=new ArrayList<>();
    private final LinkedHashMap<String,Knowledge> db=new LinkedHashMap<>();
    private BluetoothAdapter adapter; private BluetoothLeScanner scanner; private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx,tx; private boolean connected,connecting,ready,capturing=true;
    private byte[] lastSnapshot; private int selA=-1,selB=-1;
    private TextView status,count,selection; private LinearLayout list; private Button search,capture,compare,disconnect;

    private final Runnable scanTimeout=()->{stopScan();if(!connected)setStatus("G28 não encontrado");};
    private final ScanCallback scanCb=new ScanCallback(){
        @Override public void onScanResult(int t,ScanResult r){BluetoothDevice d=r.getDevice();String n=safeName(d);if((n==null||n.isEmpty())&&r.getScanRecord()!=null)n=r.getScanRecord().getDeviceName();if(n!=null&&n.toUpperCase(Locale.US).contains("G28")){stopScan();connect(d);}}
        @Override public void onScanFailed(int e){connecting=false;setStatus("Falha no scanner: "+e);updateUi();}
    };
    private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt c,int s,int ns){if(s==BluetoothGatt.GATT_SUCCESS&&ns==BluetoothProfile.STATE_CONNECTED){connected=true;connecting=false;setStatus("Conectado. Descobrindo serviços...");try{c.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);}catch(Exception ignored){}c.discoverServices();}else if(ns==BluetoothProfile.STATE_DISCONNECTED||s!=BluetoothGatt.GATT_SUCCESS){release(c);setStatus("Desconectado");}updateUi();}
        @Override public void onServicesDiscovered(BluetoothGatt c,int s){if(s!=BluetoothGatt.GATT_SUCCESS)return;for(BluetoothGattService sv:c.getServices())for(BluetoothGattCharacteristic ch:sv.getCharacteristics()){if(RX.equals(ch.getUuid()))rx=ch;if(TX.equals(ch.getUuid()))tx=ch;}enableNotify();}
        @Override public void onDescriptorWrite(BluetoothGatt c,BluetoothGattDescriptor d,int s){ready=s==BluetoothGatt.GATT_SUCCESS;setStatus(ready?"G28 pronto | explorador ativo":"Falha ao ativar notificações: "+s);updateUi();}
        @Override public void onCharacteristicChanged(BluetoothGatt c,BluetoothGattCharacteristic ch){handle(ch.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt c,BluetoothGattCharacteristic ch,byte[] v){handle(v);}
    };

    @Override protected void onCreate(Bundle b){super.onCreate(b);BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=m==null?null:m.getAdapter();load();seed();build();ensurePermissions();}

    private void build(){int p=dp(10);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(p,p,p,p);
        TextView title=new TextView(this);title.setText("ORBIS WATCH LAB v"+VERSION+" — PROTOCOL EXPLORER");title.setTextSize(20);title.setTypeface(null,1);root.addView(title);
        status=new TextView(this);status.setText("Pronto para procurar o G28");status.setTextSize(16);status.setPadding(0,dp(5),0,dp(3));root.addView(status);
        count=new TextView(this);count.setTextSize(13);root.addView(count);
        LinearLayout r1=row();search=button("PROCURAR G28",v->startScan());capture=button("PAUSAR CAPTURA",v->toggleCapture());add(r1,search);add(r1,capture);root.addView(r1);
        LinearLayout r2=row();add(r2,button("LER DF 00",v->send(new byte[]{(byte)0xDF,0})));add(r2,button("COMANDO TESTE",v->send(TEST)));root.addView(r2);
        LinearLayout r3=row();compare=button("COMPARAR 2",v->compare());add(r3,compare);add(r3,button("BANCO",v->showDb()));add(r3,button("EXPORTAR",v->export()));root.addView(r3);
        LinearLayout r4=row();add(r4,button("LIMPAR",v->clearConfirm()));add(r4,button("NOVA BASE",v->{lastSnapshot=null;toast("Próximo DF 00 4C será a base");}));disconnect=button("DESCONECTAR",v->disconnect());add(r4,disconnect);root.addView(r4);
        selection=new TextView(this);selection.setTextSize(13);selection.setPadding(0,dp(3),0,dp(3));root.addView(selection);
        TextView hint=new TextView(this);hint.setText("Toque para classificar. Segure duas capturas para comparar byte a byte.");hint.setTextSize(13);root.addView(hint);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sc=new ScrollView(this);sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);refresh();updateUi();}

    private void handle(byte[] v){if(v==null||!capturing)return;byte[] data=Arrays.copyOf(v,v.length);Capture c=new Capture();c.time=System.currentTimeMillis();c.hex=hex(data);c.type=type(data);c.signature=signature(data);c.length=data.length;
        Knowledge k=db.get(c.signature);if(k==null)k=db.get(c.type);if(k!=null)c.apply(k);
        if(isSnapshot(data)){c.name="Snapshot geral";c.status="CONFIRMADO";c.action="Snapshot";c.note=snapshot(data);}
        captures.add(0,c);while(captures.size()>MAX)captures.remove(captures.size()-1);save();runOnUiThread(this::refresh);}

    private String snapshot(byte[] now){StringBuilder s=new StringBuilder();if(now.length>49){int lang=now[49]&255;s.append("Idioma: ").append(langName(lang)).append(" [").append(two(lang)).append("]");}if(lastSnapshot==null){lastSnapshot=Arrays.copyOf(now,now.length);return s+" | base capturada";}String d=diff(lastSnapshot,now,false);lastSnapshot=Arrays.copyOf(now,now.length);return s+" | "+d;}

    private void refresh(){if(list==null)return;list.removeAllViews();count.setText("Capturas: "+captures.size()+" | Banco: "+db.size()+" | "+(capturing?"capturando":"pausado"));selection.setText("Comparação: "+label(selA)+" | "+label(selB));
        for(int i=0;i<captures.size();i++){final int pos=i;Capture c=captures.get(i);TextView card=new TextView(this);String mark="CONFIRMADO".equals(c.status)?"✓":"HIPÓTESE".equals(c.status)?"?":"•";String chosen=(i==selA||i==selB)?" [SELECIONADO]":"";card.setText(mark+" "+time.format(new Date(c.time))+"  "+(c.name.isEmpty()?"Evento desconhecido":c.name)+(c.action.isEmpty()?"":" — "+c.action)+chosen+"\n"+c.type+" | "+c.length+" bytes | "+c.status+(c.note.isEmpty()?"":"\n"+c.note)+"\n"+c.hex);card.setTextSize(12);card.setTypeface(android.graphics.Typeface.MONOSPACE);card.setPadding(dp(8),dp(8),dp(8),dp(8));card.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);card.setOnClickListener(v->classify(pos));card.setOnLongClickListener(v->{select(pos);return true;});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(6));list.addView(card,lp);}}

    private void classify(int pos){if(pos<0||pos>=captures.size())return;Capture c=captures.get(pos);LinearLayout f=new LinearLayout(this);f.setOrientation(LinearLayout.VERTICAL);f.setPadding(dp(16),0,dp(16),0);TextView packet=new TextView(this);packet.setText(c.type+" | "+c.length+" bytes\n"+c.hex);packet.setTextSize(11);packet.setTypeface(android.graphics.Typeface.MONOSPACE);f.addView(packet);EditText name=edit("Nome da função",c.name);f.addView(name);Spinner category=spinner(new String[]{"Sistema","Saúde","Display","Bluetooth","OTA","Notificação","Outro"},c.category);f.addView(category);Spinner action=spinner(new String[]{"Alterado","Ligado","Desligado","Evento","Snapshot","Resposta","Comando"},c.action);f.addView(action);Spinner stat=spinner(new String[]{"DESCONHECIDO","HIPÓTESE","CONFIRMADO"},c.status);f.addView(stat);EditText note=edit("Observação",c.note);f.addView(note);
        new AlertDialog.Builder(this).setTitle("Classificar captura").setView(f).setNegativeButton("Cancelar",null).setNeutralButton("Excluir",(d,w)->{captures.remove(pos);save();refresh();}).setPositiveButton("Salvar",(d,w)->{c.name=name.getText().toString().trim();c.category=String.valueOf(category.getSelectedItem());c.action=String.valueOf(action.getSelectedItem());c.status=String.valueOf(stat.getSelectedItem());c.note=note.getText().toString().trim();Knowledge k=new Knowledge();k.key=c.signature;k.type=c.type;k.name=c.name;k.category=c.category;k.action=c.action;k.status=c.status;k.note=c.note;k.example=c.hex;k.updated=System.currentTimeMillis();db.put(k.key,k);for(Capture x:captures)if(x.signature.equals(k.key))x.apply(k);save();refresh();}).show();}

    private void select(int p){if(selA==p)selA=-1;else if(selB==p)selB=-1;else if(selA<0)selA=p;else if(selB<0)selB=p;else{selA=p;selB=-1;}refresh();}
    private void compare(){if(selA<0||selB<0||selA>=captures.size()||selB>=captures.size()){toast("Segure duas capturas");return;}Capture a=captures.get(selA),b=captures.get(selB);String text="A: "+time.format(new Date(a.time))+" — "+a.type+"\nB: "+time.format(new Date(b.time))+" — "+b.type+"\n\n"+diff(parse(a.hex),parse(b.hex),true);new AlertDialog.Builder(this).setTitle("Comparação byte a byte").setMessage(text).setNegativeButton("Fechar",null).setNeutralButton("Limpar seleção",(d,w)->{selA=selB=-1;refresh();}).setPositiveButton("Copiar",(d,w)->copy(text)).show();}

    private String diff(byte[] a,byte[] b,boolean detailed){int max=Math.max(a.length,b.length),n=0;StringBuilder out=new StringBuilder();for(int i=0;i<max;i++){String x=i<a.length?two(a[i]&255):"--",y=i<b.length?two(b[i]&255):"--";if(!x.equals(y)){n++;if(detailed)out.append("offset ").append(i).append(" (byte ").append(i+1).append("): ").append(x).append(" → ").append(y).append(i==49?" | IDIOMA":i==3?" | CONTROLE/DERIVADO":"").append('\n');else{if(out.length()>0)out.append("; ");out.append("off ").append(i).append(": ").append(x).append("→").append(y);}}}return "Diferenças: "+n+(n==0?" | pacotes idênticos":detailed?"\n"+out:" | "+out);}

    private void showDb(){if(db.isEmpty()){toast("Banco vazio");return;}StringBuilder s=new StringBuilder();ArrayList<Knowledge> ks=new ArrayList<>(db.values());ks.sort((a,b)->Long.compare(b.updated,a.updated));for(Knowledge k:ks)s.append('[').append(k.status).append("] ").append(k.name.isEmpty()?"Sem nome":k.name).append(k.action.isEmpty()?"":" — "+k.action).append('\n').append(k.type).append(" | ").append(k.key).append('\n').append(k.category).append(k.note.isEmpty()?"":" | "+k.note).append("\n\n");new AlertDialog.Builder(this).setTitle("Banco do protocolo — "+db.size()).setMessage(s.toString()).setNegativeButton("Fechar",null).setPositiveButton("Copiar JSON",(d,w)->copy(json().toString())).show();}

    private void export(){String text=json().toString();copy(text);if(Build.VERSION.SDK_INT>=29)try{String name="orbis_g28_protocol_"+fileTime.format(new Date())+".json";ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,name);v.put(MediaStore.Downloads.MIME_TYPE,"application/json");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/OrbisWatchLab");android.net.Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("URI nula");try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)throw new Exception("arquivo indisponível");os.write(text.getBytes(StandardCharsets.UTF_8));}toast("JSON salvo em Downloads/OrbisWatchLab");}catch(Exception e){toast("JSON copiado; falha ao salvar: "+e.getMessage());}else toast("JSON copiado");}

    private JSONObject json(){JSONObject root=new JSONObject();try{root.put("app","Orbis Watch Lab");root.put("version",VERSION);root.put("exportedAt",System.currentTimeMillis());JSONArray k=new JSONArray();for(Knowledge x:db.values())k.put(x.json());root.put("knowledge",k);JSONArray c=new JSONArray();for(Capture x:captures)c.put(x.json());root.put("captures",c);}catch(Exception ignored){}return root;}

    private void seed(){if(!db.containsKey("DF 00 4C")){Knowledge k=new Knowledge();k.key="DF 00 4C";k.type=k.key;k.name="Snapshot geral";k.category="Sistema";k.action="Snapshot";k.status="CONFIRMADO";k.note="offset 49 = idioma; offset 3 = idioma + 0x11";k.updated=System.currentTimeMillis();db.put(k.key,k);}}
    private void toggleCapture(){capturing=!capturing;capture.setText(capturing?"PAUSAR CAPTURA":"RETOMAR CAPTURA");updateUi();}
    private void clearConfirm(){new AlertDialog.Builder(this).setTitle("Limpar capturas?").setMessage("O banco será preservado.").setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(d,w)->{captures.clear();selA=selB=-1;save();refresh();}).show();}

    private void startScan(){if(!ensurePermissions())return;if(connected||connecting||gatt!=null){toast("Já existe conexão ativa");return;}if(adapter==null||!adapter.isEnabled()){toast("Ative o Bluetooth");return;}scanner=adapter.getBluetoothLeScanner();if(scanner==null){toast("Scanner indisponível");return;}connecting=true;setStatus("Procurando G28...");scanner.startScan(scanCb);h.postDelayed(scanTimeout,SCAN_MS);updateUi();}
    private void stopScan(){h.removeCallbacks(scanTimeout);if(scanner!=null&&hasScan())try{scanner.stopScan(scanCb);}catch(Exception ignored){}scanner=null;}
    private void connect(BluetoothDevice d){if(!hasConnect()||gatt!=null)return;connecting=true;setStatus("Conectando a "+safeAddress(d));gatt=d.connectGatt(this,false,gattCb,BluetoothDevice.TRANSPORT_LE);updateUi();}
    private void enableNotify(){if(gatt==null||tx==null||!hasConnect())return;try{gatt.setCharacteristicNotification(tx,true);BluetoothGattDescriptor d=tx.getDescriptor(CCCD);if(d==null){setStatus("CCCD não encontrado");return;}byte[] v=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;if(Build.VERSION.SDK_INT>=33)gatt.writeDescriptor(d,v);else{d.setValue(v);gatt.writeDescriptor(d);}}catch(Exception e){setStatus("Erro de notify: "+e.getMessage());}}
    private void send(byte[] data){if(!ready||gatt==null||rx==null||!hasConnect()){toast("Conecte ao G28 primeiro");return;}try{if(Build.VERSION.SDK_INT>=33)gatt.writeCharacteristic(rx,data,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);else{rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);rx.setValue(data);gatt.writeCharacteristic(rx);}}catch(Exception e){toast("Erro TX: "+e.getMessage());}}
    private void release(BluetoothGatt c){if(c!=null)try{c.close();}catch(Exception ignored){}if(c==gatt)gatt=null;connected=connecting=ready=false;rx=tx=null;}
    private void disconnect(){stopScan();if(gatt!=null)try{gatt.disconnect();}catch(Exception ignored){}release(gatt);setStatus("Desconectado");updateUi();}

    private void load(){SharedPreferences p=getSharedPreferences("orbis_v17",MODE_PRIVATE);try{JSONArray a=new JSONArray(p.getString("captures","[]"));for(int i=0;i<a.length();i++)captures.add(Capture.from(a.getJSONObject(i)));}catch(Exception ignored){captures.clear();}try{JSONArray a=new JSONArray(p.getString("knowledge","[]"));for(int i=0;i<a.length();i++){Knowledge k=Knowledge.from(a.getJSONObject(i));db.put(k.key,k);}}catch(Exception ignored){db.clear();}}
    private void save(){try{JSONArray c=new JSONArray();for(Capture x:captures)c.put(x.json());JSONArray k=new JSONArray();for(Knowledge x:db.values())k.put(x.json());getSharedPreferences("orbis_v17",MODE_PRIVATE).edit().putString("captures",c.toString()).putString("knowledge",k.toString()).apply();}catch(Exception ignored){}}
    private boolean ensurePermissions(){ArrayList<String> m=new ArrayList<>();if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)m.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)m.add(Manifest.permission.BLUETOOTH_CONNECT);}else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)m.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!m.isEmpty()){requestPermissions(m.toArray(new String[0]),REQ);return false;}return true;}
    private boolean hasScan(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;}
    private boolean hasConnect(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private String safeName(BluetoothDevice d){if(!hasConnect())return null;try{return d.getName();}catch(Exception e){return null;}}
    private String safeAddress(BluetoothDevice d){if(!hasConnect())return"?";try{return d.getAddress();}catch(Exception e){return"?";}}
    private String type(byte[] v){return v.length>=3?two(v[0]&255)+" "+two(v[1]&255)+" "+two(v[2]&255):hex(v);}
    private String signature(byte[] v){StringBuilder s=new StringBuilder(type(v)).append('-').append(v.length).append("B-");for(int i=3;i<Math.min(v.length,16);i++){if(i>3)s.append('.');s.append(two(v[i]&255));}return s.toString();}
    private boolean isSnapshot(byte[] v){return v.length>=4&&(v[0]&255)==0xDF&&(v[1]&255)==0&&(v[2]&255)==0x4C;}
    private String langName(int x){switch(x){case 0:return"Inglês";case 4:return"Espanhol";case 6:return"Português";case 0x1C:return"22º idioma";default:return"desconhecido";}}
    private String label(int p){return p>=0&&p<captures.size()?(p==selA?"A=":"B=")+time.format(new Date(captures.get(p).time)):(p==selA?"A=—":"B=—");}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private Button button(String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setTextSize(10);b.setOnClickListener(l);return b;}
    private void add(LinearLayout r,View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));r.addView(v,p);}
    private EditText edit(String h,String v){EditText e=new EditText(this);e.setHint(h);e.setText(v==null?"":v);return e;}
    private Spinner spinner(String[] v,String selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,v));for(int i=0;i<v.length;i++)if(v[i].equals(selected)){s.setSelection(i);break;}return s;}
    private void setStatus(String s){runOnUiThread(()->status.setText(s));}
    private void updateUi(){runOnUiThread(()->{search.setEnabled(!connected&&!connecting&&gatt==null);disconnect.setEnabled(gatt!=null||connected||connecting);compare.setEnabled(selA>=0&&selB>=0);count.setText("Capturas: "+captures.size()+" | Banco: "+db.size()+" | "+(capturing?"capturando":"pausado")+(ready?" | BLE pronto":""));});}
    private void copy(String s){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null){c.setPrimaryClip(ClipData.newPlainText("Orbis Watch Lab",s));toast("Copiado");}}
    private static String two(int x){return String.format(Locale.US,"%02X",x&255);}
    private static String hex(byte[] v){if(v==null||v.length==0)return"(vazio)";StringBuilder s=new StringBuilder();for(byte b:v){if(s.length()>0)s.append(' ');s.append(two(b&255));}return s.toString();}
    private static byte[] parse(String s){String x=s.replaceAll("[^0-9A-Fa-f]","");byte[] b=new byte[x.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show());}
    @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);disconnect();super.onDestroy();}

    static class Capture{long time;int length;String type="",signature="",hex="",name="",category="",action="",status="DESCONHECIDO",note="";void apply(Knowledge k){name=k.name;category=k.category;action=k.action;status=k.status;}JSONObject json(){JSONObject o=new JSONObject();try{o.put("time",time);o.put("length",length);o.put("type",type);o.put("signature",signature);o.put("hex",hex);o.put("name",name);o.put("category",category);o.put("action",action);o.put("status",status);o.put("note",note);}catch(Exception ignored){}return o;}static Capture from(JSONObject o){Capture c=new Capture();c.time=o.optLong("time");c.length=o.optInt("length");c.type=o.optString("type");c.signature=o.optString("signature");c.hex=o.optString("hex");c.name=o.optString("name");c.category=o.optString("category");c.action=o.optString("action");c.status=o.optString("status","DESCONHECIDO");c.note=o.optString("note");return c;}}
    static class Knowledge{long updated;String key="",type="",name="",category="",action="",status="DESCONHECIDO",note="",example="";JSONObject json(){JSONObject o=new JSONObject();try{o.put("updated",updated);o.put("key",key);o.put("type",type);o.put("name",name);o.put("category",category);o.put("action",action);o.put("status",status);o.put("note",note);o.put("example",example);}catch(Exception ignored){}return o;}static Knowledge from(JSONObject o){Knowledge k=new Knowledge();k.updated=o.optLong("updated");k.key=o.optString("key");k.type=o.optString("type");k.name=o.optString("name");k.category=o.optString("category");k.action=o.optString("action");k.status=o.optString("status","DESCONHECIDO");k.note=o.optString("note");k.example=o.optString("example");return k;}}
}
