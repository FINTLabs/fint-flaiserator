package no.fintlabs.operator.crd;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Prometheus {
    private boolean enabled;
    private String path;
    private String port;
}
