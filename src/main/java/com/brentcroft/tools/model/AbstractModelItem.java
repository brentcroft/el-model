package com.brentcroft.tools.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;

@Getter
@Setter
public abstract class AbstractModelItem extends LinkedHashMap<String,Object> implements Model
{
    private static JsonMapper jsonMapper = JsonMapper
            .builder()
            .enable( JsonReadFeature.ALLOW_JAVA_COMMENTS )
            .enable( JsonReadFeature.ALLOW_SINGLE_QUOTES )
            .enable( JsonReadFeature.ALLOW_YAML_COMMENTS )
            .enable( JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES )
            .enable( JsonReadFeature.ALLOW_TRAILING_COMMA )
            .build();

    private static BiFunction<String, Model, String> expander = ( value, context) -> value;
    private static BiFunction<String, Model, Object> evaluator = ( value, context) -> value;

    public static void setExpander( BiFunction<String, Model, String> expander ) {
        AbstractModelItem.expander = expander;
    }
    public static BiFunction<String, Model, String> getExpander() {
        return AbstractModelItem.expander;
    }
    public static void setEvaluator(BiFunction<String, Model, Object> evaluator) {
        AbstractModelItem.evaluator = evaluator;
    }
    public static BiFunction<String, Model, Object> getEvaluator() {
        return AbstractModelItem.evaluator;
    }
    public static void setJsonMapper(JsonMapper jsonMapper) {
        AbstractModelItem.jsonMapper = jsonMapper;
    }
    public static JsonMapper getJsonMapper() {
        return AbstractModelItem.jsonMapper;
    }

    private String name;
    private Map<String, Object> parent;

    public Object get(Object key) {
        return Optional
                .ofNullable( super.get( key ) )
                .map( p -> p instanceof String ? expand((String)p) : p)
                .orElseGet( () -> Optional
                        .ofNullable( getParent() )
                        .map( p -> {
                            Object v = p.get(key);
                            return  v instanceof String ? expand((String)v) : v;
                        })
                        .orElse( null ));
    }

    public Object put(String key, Object value) {
        if ( value instanceof Model ) {
            (( Model )value).setParent( this );
        }
        return super.put(key, value);
    }

    private File getCurrentDirectory()
    {
        return Optional
                .ofNullable( (String)get("$currentDirectory") )
                .map(File::new)
                .orElse( null );
    }

    private void setCurrentDirectory( File file )
    {
        File cd = getCurrentDirectory();
        if ( cd == null || !cd.equals( file ) ) {
            put("$currentDirectory", file.getPath());
        }
    }

    public Model newItemFromJson( String jsonText) {
        try
        {
            return jsonMapper.readValue( jsonText, getModelClass());
        }
        catch ( JsonProcessingException e )
        {
            throw new IllegalArgumentException(format("JSON text failed to materialize: %s", jsonText), e);
        }
    }

    public void introspectEntries() {
        if (containsKey( "$json" )) {
            Model item = newChild( this, readFileFully(get("$json").toString()) );
            putAll( item );
        }
        if (containsKey( "$properties" )) {
            overwritePropertiesFromFile(get("$properties").toString());
        }
    }

    public void filteredPutAll( Map< ? extends String, ? > item) {
        putAll( item
                .entrySet()
                .stream()
                .filter( entry -> ! entry.getKey().startsWith( "$json" ))
                .filter( entry -> ! entry.getKey().startsWith( "$properties" ))
                .collect( Collectors.toMap( Map.Entry::getKey, Map.Entry::getValue ) ));
    }

    @SuppressWarnings( "unchecked" )
    private void overwritePropertiesFromFile( String propertiesFilePath )
    {
        File file = new File(propertiesFilePath);
        if (!file.exists()) {
            file = new File( getCurrentDirectory(), propertiesFilePath );
        }
        final Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream( file )) {
            p.load( fis );
        } catch (Exception e) {
            throw new IllegalArgumentException(format( "Properties file not found: %s",propertiesFilePath ), e);
        }
        Function<String,String> uptoAnyPlaceHolder = value -> {
                int phi = value.indexOf("{0}");
                if (phi > -1) {
                    return value.substring( 0, phi );
                }
                return value;
        };
        p.forEach( (k,v) -> {
            final String ref = k.toString().trim();
            final String value = uptoAnyPlaceHolder.apply(v.toString().trim());
            final String[] segs = ref.split( "\\s*\\.\\s*" );
            Map<String,Object> target = this;
            for (int i = 0, n = segs.length; i< n; i++) {
                final Object segValue = target.get( segs[i] );
                if (segValue instanceof Map) {
                    target = (Map<String,Object>)segValue;
                    continue;
                }
                final String key = Arrays
                        .stream( segs, i, n )
                        .collect( Collectors.joining("."));

                target.put( key, value );
            }
        } );
    }

    public String readFileFully( String filePath )
    {
        File file = new File(filePath);
        if (!file.exists()) {
            file = new File( getCurrentDirectory(), filePath );
        }
        if (!file.exists()) {
            throw new IllegalArgumentException(format("File does not exist: %s", file));
        }
        setCurrentDirectory(file.getParentFile());
        try
        {
            return String
                    .join( "\n", Files
                    .readAllLines( file.toPath() ) );
        }
        catch ( IOException e )
        {
           throw new IllegalArgumentException(format("Invalid file path: %s", filePath), e);
        }
    }


    /**
     * Expands a value using the expander
     * or else just returns the value.
     *
     * @param value the value to be expanded
     * @return the expanded value
     */
    @Override
    public String expand( String value )
    {
        return Optional
            .ofNullable(expander)
            .map(exp -> exp.apply( value, this ) )
            .orElse( value );
    }
    /**
     * Evaluates a value using the evaluator
     * or else just returns the value.
     *
     * @param value the value to be evaluated
     * @return the evaluated value
     */
    @Override
    public Object eval( String value )
    {
        return Optional
                .ofNullable(evaluator)
                .map(exp -> exp.apply( value, this ) )
                .orElse( value );
    }

    @Override
    public String  toJson()
    {
        try
        {
            return jsonMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString( this );
        }
        catch ( JsonProcessingException e )
        {
            throw new IllegalArgumentException(format("ModelItem at path: %s", path()), e);
        }
    }

    public String toString() {
        return path();
    }

    @Override
    public Model getSelf() {
        return this;
    }
}
