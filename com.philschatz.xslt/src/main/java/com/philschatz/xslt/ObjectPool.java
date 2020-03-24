package com.philschatz.xslt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

class ObjectKey<O> {
	public Long id;
  public O owner;
  
  ObjectKey(Long id, O owner) {
    this.id = id;
    this.owner = owner;
  }
}

class ObjectMapping<O, V> {
	public ObjectKey<O> key;
  public V value;
  ObjectMapping(ObjectKey<O> key, V value) {
    this.key = key;
    this.value = value;
  }
}

/** from https://github.com/fwcd/kotlin-debug-adapter  */
class ObjectPool<O, V> {
  private static long currentID = 1000L;

  public static class Unit {
    private Unit() { /* Singleton */ }
  }
  public static Unit UNIT = new Unit();

  private HashMap<Long, ObjectMapping<O, V>> mappingsByID = new HashMap<>();
  private HashMap<O, HashSet<ObjectMapping<O, V>>> mappingsByOwner = new HashMap<>();
  
  public boolean isEmpty() { return mappingsByID.isEmpty(); }
  public int size() { return mappingsByID.size(); }
  public void clear() { mappingsByID.clear(); mappingsByOwner.clear(); }

  public long store(O owner, V value) {
    long id = ObjectPool.currentID;
    ObjectKey<O> key = new ObjectKey<>(id, owner);
    ObjectMapping<O, V> mapping = new ObjectMapping<>(key, value);
    mappingsByID.put(id, mapping);
    mappingsByOwner.putIfAbsent(owner, new HashSet<ObjectMapping<O, V>>());
    mappingsByOwner.get(owner).add(mapping);
    ObjectPool.currentID++;
    return id;
  }


  public void removeAllOwnedBy(O owner) {
    HashSet<ObjectMapping<O, V>> mappings = mappingsByOwner.get(owner);
    if (mappings != null) {
      for (ObjectMapping<O, V> mapping : mappings) {
        mappingsByID.remove(mapping.key.id);
      }
    }
    mappingsByOwner.remove(owner);
  }

  public void removeByID(long id) {
    ObjectMapping<O, V> it = mappingsByID.get(id);
    if (it != null) {
      HashSet<ObjectMapping<O, V>> o = mappingsByOwner.get(it.key.owner);
      if (o != null) {
        o.remove(it);
      }
    }
    mappingsByID.remove(id);
  }
	
  public V getById(long id) {
    ObjectMapping<O, V> it = mappingsByID.get(id);
    return (it == null) ? null : it.value;
  }
	
  public Set<V> getOwnedBy(O owner) {
    HashSet<ObjectMapping<O, V>> it = mappingsByOwner.get(owner);
    HashSet<V> ret = new HashSet<>();
    if (it == null) { return ret; }
    for (ObjectMapping<O, V> m : it) {
      ret.add(m.value);
    }
    return ret;
  }
	
  public boolean containsID(long id) { return mappingsByID.containsKey(id); }

}
