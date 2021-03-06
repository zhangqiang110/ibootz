package top.bootz.security.web.entity;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@Table(name = "demo_authority")
public class Authority implements Serializable {

    private static final long serialVersionUID = 1L;

    protected Long id;

    private String name;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, unique = true, updatable = false, insertable = false, columnDefinition = "int(11) default 0 comment '主键唯一标识'")
    public Long getId() {
        return id;
    }

    @Column(name = "name", nullable = false, unique = true, columnDefinition = "varchar(128) default '' comment '权限字符串'")
    public String getName() {
        return name;
    }

}
