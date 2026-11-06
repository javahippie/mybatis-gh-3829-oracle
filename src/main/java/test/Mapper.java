package test;

import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface Mapper {

  List<User> getUsers(@Param("ids") List<Integer> ids, @Param("maxRows") int maxRows);
}
