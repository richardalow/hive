package org.apache.hadoop.hive.serde2.typeinfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.hive.serde.Constants;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

public class TypeInfoUtils {
  
  /**
   * Parse a recursive TypeInfo list String.
   * For example, the following inputs are valid inputs: 
   *  "int,string,map<string,int>,list<map<int,list<string>>>,list<struct<a:int,b:string>>"
   * The separators between TypeInfos can be ",", ":", or ";".
   * 
   * In order to use this class:
   * TypeInfoParser parser = new TypeInfoParser("int,string");
   * ArrayList<TypeInfo> typeInfos = parser.parseTypeInfos();
   */
  private static class TypeInfoParser {
    
    static final String STRUCT_TYPE_NAME = "struct";
    
    private static class Token {
      public int position;
      public String text;
      public boolean isAlphaDigit;
    };
    
    /**
     * Tokenize the typeInfoString.
     * The rule is simple: all consecutive alphadigits are in one token, and all 
     * other characters are one character per token.
     * 
     * tokenize("map<int,string>") should return ["map","<","int",",","string",">"]
     */
    private static ArrayList<Token> tokenize(String typeInfoString) {
      ArrayList<Token> tokens = new ArrayList<Token>(0);
      int begin = 0;
      int end = 1;
      while (end <= typeInfoString.length()) {
        // last character ends a token? 
        if (end == typeInfoString.length() 
            || !Character.isLetterOrDigit(typeInfoString.charAt(end-1))
            || !Character.isLetterOrDigit(typeInfoString.charAt(end))) {
          Token t = new Token();
          t.position = begin;
          t.text = typeInfoString.substring(begin, end);
          t.isAlphaDigit = Character.isLetterOrDigit(typeInfoString.charAt(begin));
          tokens.add(t);
          begin = end;
        }          
        end ++;
      }
      return tokens;
    }
  
    public TypeInfoParser(String typeInfoString) {
      this.typeInfoString = typeInfoString;
      this.typeInfoTokens = tokenize(typeInfoString);
    }
  
    private String typeInfoString;
    private ArrayList<Token> typeInfoTokens;
    private ArrayList<TypeInfo> typeInfos;
    private int iToken;
    
    public ArrayList<TypeInfo> parseTypeInfos() throws IllegalArgumentException {
      typeInfos = new ArrayList<TypeInfo>();
      iToken = 0;
      while (iToken < typeInfoTokens.size()) {
        typeInfos.add(parseType());
        if (iToken < typeInfoTokens.size()) {
          Token separator = typeInfoTokens.get(iToken);
          if (",".equals(separator.text) || ";".equals(separator.text) || ":".equals(separator.text)) {
            iToken ++;
          } else {
            throw new IllegalArgumentException("Error: ',', ':', or ';' expected at position " 
                + separator.position + " from '" + typeInfoString + "'" );
          }
        }
      }
      return typeInfos;
    }
  
    private Token expect(String item) {
      return expect(item, null);
    }
    
    private Token expect(String item, String alternative) {
      if (iToken >= typeInfoTokens.size()) {
        throw new IllegalArgumentException("Error: " + item + " expected at the end of '"  
            + typeInfoString + "'" );
      }
      Token t = typeInfoTokens.get(iToken);
      if (item.equals("type")) {
        if (!Constants.LIST_TYPE_NAME.equals(t.text)
            && !Constants.MAP_TYPE_NAME.equals(t.text)
            && !STRUCT_TYPE_NAME.equals(t.text)
            && null == ObjectInspectorUtils.typeNameToClass.get(t.text)
            && !t.text.equals(alternative)) {
          throw new IllegalArgumentException("Error: " + item + " expected at the position "
              + t.position + " of '" + typeInfoString + "'" );
        }
      } else if (item.equals("name")) {
        if (!t.isAlphaDigit && !t.text.equals(alternative)) {
          throw new IllegalArgumentException("Error: " + item + " expected at the position "
              + t.position + " of '" + typeInfoString + "'" );
        }
      } else {
        if (!item.equals(t.text) && !t.text.equals(alternative)) {
          throw new IllegalArgumentException("Error: " + item + " expected at the position "
              + t.position + " of '" + typeInfoString + "'" );
        }
      }
      iToken ++;
      return t;
    }
    
    private TypeInfo parseType() {
      
      Token t = expect("type");
  
      // Is this a primitive type?
      Class<?> clazz = ObjectInspectorUtils.typeNameToClass.get(t.text);
      if (clazz != null) {
        return TypeInfoFactory.getPrimitiveTypeInfo(clazz);
      }
      
      // Is this a list type?
      if (Constants.LIST_TYPE_NAME.equals(t.text)) {
        expect("<");
        TypeInfo listElementType = parseType();
        expect(">");
        return TypeInfoFactory.getListTypeInfo(listElementType);
      }
  
      // Is this a map type?
      if (Constants.MAP_TYPE_NAME.equals(t.text)) {
        expect("<");
        TypeInfo mapKeyType = parseType();
        expect(",");
        TypeInfo mapValueType = parseType();
        expect(">");
        return TypeInfoFactory.getMapTypeInfo(mapKeyType, mapValueType);
      }
  
      // Is this a struct type?
      if (STRUCT_TYPE_NAME.equals(t.text)) {
        ArrayList<String> fieldNames = new ArrayList<String>();
        ArrayList<TypeInfo> fieldTypeInfos = new ArrayList<TypeInfo>();
        boolean first = true;
        do {
          if (first) {
            expect("<");
            first = false;
          } else {
            Token separator = expect(">", ",");
            if (separator.text.equals(">")) {
              // end of struct
              break;
            }
          }
          Token name = expect("name");
          fieldNames.add(name.text);
          expect(":");
          fieldTypeInfos.add(parseType());
        } while (true);
        
        return TypeInfoFactory.getStructTypeInfo(fieldNames, fieldTypeInfos);
      }
  
      throw new RuntimeException("Internal error parsing position " + t.position + " of '"
          + typeInfoString + "'");
    }
    
  }

  static HashMap<TypeInfo, ObjectInspector> cachedStandardObjectInspector = new HashMap<TypeInfo, ObjectInspector>();
  /**
   * Returns the standard object inspector that can be used to translate an object of that typeInfo
   * to a standard object type.  
   */
  public static ObjectInspector getStandardObjectInspectorFromTypeInfo(TypeInfo typeInfo) {
    ObjectInspector result = cachedStandardObjectInspector.get(typeInfo);
    if (result == null) {
      switch(typeInfo.getCategory()) {
        case PRIMITIVE: {
          result = ObjectInspectorFactory.getStandardPrimitiveObjectInspector(typeInfo.getPrimitiveClass());
          break;
        }
        case LIST: {
          ObjectInspector elementObjectInspector = getStandardObjectInspectorFromTypeInfo(typeInfo.getListElementTypeInfo());
          result = ObjectInspectorFactory.getStandardListObjectInspector(elementObjectInspector);
          break;
        }
        case MAP: {
          ObjectInspector keyObjectInspector = getStandardObjectInspectorFromTypeInfo(typeInfo.getMapKeyTypeInfo());
          ObjectInspector valueObjectInspector = getStandardObjectInspectorFromTypeInfo(typeInfo.getMapValueTypeInfo());
          result = ObjectInspectorFactory.getStandardMapObjectInspector(keyObjectInspector, valueObjectInspector);
          break;
        }
        case STRUCT: {
          List<String> fieldNames = typeInfo.getAllStructFieldNames();
          List<TypeInfo> fieldTypeInfos = typeInfo.getAllStructFieldTypeInfos();
          List<ObjectInspector> fieldObjectInspectors = new ArrayList<ObjectInspector>(fieldTypeInfos.size());
          for(int i=0; i<fieldTypeInfos.size(); i++) {
            fieldObjectInspectors.add(getStandardObjectInspectorFromTypeInfo(fieldTypeInfos.get(i)));
          }
          result = ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldObjectInspectors);
          break;
        }
        default: {
          result = null;
        }
      }
      cachedStandardObjectInspector.put(typeInfo, result);
    }
    return result;
  }

  
  /**
   * Get the TypeInfo object from the ObjectInspector object by recursively going into the
   * ObjectInspector structure.
   */
  public static TypeInfo getTypeInfoFromObjectInspector(ObjectInspector oi) {
//    OPTIMIZATION for later.
//    if (oi instanceof TypeInfoBasedObjectInspector) {
//      TypeInfoBasedObjectInspector typeInfoBasedObjectInspector = (ObjectInspector)oi;
//      return typeInfoBasedObjectInspector.getTypeInfo();
//    }
    
    // Recursively going into ObjectInspector structure
    TypeInfo result = null;
    switch (oi.getCategory()) {
      case PRIMITIVE: {
        PrimitiveObjectInspector poi =(PrimitiveObjectInspector)oi;
        result = TypeInfoFactory.getPrimitiveTypeInfo(poi.getPrimitiveClass());
        break;
      }
      case LIST: {
        ListObjectInspector loi = (ListObjectInspector)oi;
        result = TypeInfoFactory.getListTypeInfo(
            getTypeInfoFromObjectInspector(loi.getListElementObjectInspector()));
        break;
      }
      case MAP: {
        MapObjectInspector moi = (MapObjectInspector)oi;
        result = TypeInfoFactory.getMapTypeInfo(
            getTypeInfoFromObjectInspector(moi.getMapKeyObjectInspector()),
            getTypeInfoFromObjectInspector(moi.getMapValueObjectInspector()));
        break;
      }
      case STRUCT: {
        StructObjectInspector soi = (StructObjectInspector)oi;
        List<? extends StructField> fields = soi.getAllStructFieldRefs();
        List<String> fieldNames = new ArrayList<String>(fields.size());
        List<TypeInfo> fieldTypeInfos = new ArrayList<TypeInfo>(fields.size());
        for(StructField f : fields) {
          fieldNames.add(f.getFieldName());
          fieldTypeInfos.add(getTypeInfoFromObjectInspector(f.getFieldObjectInspector()));
        }
        result = TypeInfoFactory.getStructTypeInfo(fieldNames, fieldTypeInfos);
        break;
      }
      default: {
        throw new RuntimeException("Unknown ObjectInspector category!");
      }
    }
    return result;
  }
    
  /**
   * Return the 
   */
  public static String getTypeStringFromTypeInfo(TypeInfo typeInfo) {
    switch(typeInfo.getCategory()) {
      case PRIMITIVE: {
        return ObjectInspectorUtils.getClassShortName(typeInfo.getPrimitiveClass());
      }
      case LIST: {
        String elementType = getTypeStringFromTypeInfo(typeInfo.getListElementTypeInfo());
        return org.apache.hadoop.hive.serde.Constants.LIST_TYPE_NAME + "<" + elementType + ">";
      }
      case MAP: {
        String keyType = getTypeStringFromTypeInfo(typeInfo.getMapKeyTypeInfo());
        String valueType = getTypeStringFromTypeInfo(typeInfo.getMapValueTypeInfo());
        return org.apache.hadoop.hive.serde.Constants.MAP_TYPE_NAME + "<" +
          keyType + "," + valueType + ">";
      }
      case STRUCT: {
        StringBuilder sb = new StringBuilder();
        sb.append("struct<");
        List<String> fieldNames = typeInfo.getAllStructFieldNames();
        List<TypeInfo> fieldTypeInfos = typeInfo.getAllStructFieldTypeInfos();
        for (int i = 0; i < fieldNames.size(); i++) {
          if (i>0) sb.append(",");
          sb.append(fieldNames.get(i));
          sb.append(":");
          sb.append(getTypeStringFromTypeInfo(fieldTypeInfos.get(i)));
        }        
        sb.append(">");
        return sb.toString();
      }
      default: {
        throw new RuntimeException("Unknown type!");
      }
    }
  }
  
  public static ArrayList<TypeInfo> getTypeInfosFromTypeString(String typeString) {
    TypeInfoParser parser = new TypeInfoParser(typeString);
    return parser.parseTypeInfos();
  }

  public static TypeInfo getTypeInfoFromTypeString(String typeString) {
    TypeInfoParser parser = new TypeInfoParser(typeString);
    return parser.parseTypeInfos().get(0);
  }
}