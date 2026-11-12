package lab.financialapi.audit;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditEventMapper {
    int insert(AuditEvent event);
}
