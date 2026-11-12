package lab.financialapi.detection;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecurityAlertMapper {
    int insert(SecurityAlert alert);
}
