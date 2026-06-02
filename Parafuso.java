package robo;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Parafuso extends TeamRobot {

    // Posição e dados do alvo atual
    private double targetX, targetY, targetVelocity, targetHeading;
    private String targetName = null;
    private long targetLastSeen = 0;

    // Direção de movimento (alternado para esquiva)
    private int moveDirection = 1;
    private int strafeDirection = 1;

    // Mapa de aliados detectados (nome -> tick do último aviso)
    private Map<String, Long> teammates = new HashMap<>();

    // Constante: tempo máximo sem ver o alvo antes de resetar
    private static final long TARGET_TIMEOUT = 30;

    @Override
    public void run() {
        configurarAparencia();

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        while (true) {
            if (targetName != null && (getTime() - targetLastSeen) > TARGET_TIMEOUT) {
                targetName = null; // Alvo sumiu, procurar novo
            }

            moverEmOndas();
            rastrearRadar();
            execute();
        }
    }

    // Aparência personalizada: verde selvagem com detalhes amarelos
    private void configurarAparencia() {
        setBodyColor(new Color(34, 120, 34));       // verde floresta
        setGunColor(new Color(200, 180, 0));         // amarelo dourado
        setRadarColor(new Color(255, 220, 0));       // amarelo brilhante
        setBulletColor(new Color(255, 80, 0));       // laranja fogo
        setScanColor(new Color(0, 255, 100));        // verde neon
    }

    // Movimento em padrão de onda perpendicular ao inimigo (muito difícil de acertar)
    private void moverEmOndas() {
        if (targetName == null) {
            // Patrulha simples enquanto não tem alvo
            setAhead(100 * moveDirection);
            setTurnRight(45);
            return;
        }

        double anguloParaInimigo = Math.atan2(targetX - getX(), targetY - getY());
        double anguloPerpendiclar = anguloParaInimigo + (Math.PI / 2) * strafeDirection;

        // Vira para andar perpendicularmente ao inimigo
        double virar = Utils.normalRelativeAngle(anguloPerpendiclar - Math.toRadians(getHeading()));
        setTurnRightRadians(virar);
        setAhead(120 * moveDirection);

        // Muda direção periodicamente para imprevisibilidade
        if (Math.random() < 0.05) {
            moveDirection *= -1;
        }
    }

    // Radar de trava: fica oscilando sobre o alvo, raramente perde
    private void rastrearRadar() {
        if (targetName == null) {
            setTurnRadarRight(360);
            return;
        }

        double anguloRadarParaAlvo = Math.atan2(targetX - getX(), targetY - getY());
        double desvio = Utils.normalRelativeAngle(anguloRadarParaAlvo - Math.toRadians(getRadarHeading()));
        // Multiplica por 2 para garantir que o radar "envolva" o alvo
        setTurnRadarRightRadians(desvio * 2);
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        // Ignora aliados
        if (isTeammate(e.getName())) {
            teammates.put(e.getName(), getTime());
            return;
        }

        // Prioriza o alvo mais próximo se não há alvo definido
        double distancia = e.getDistance();
        if (targetName != null && !targetName.equals(e.getName())) {
            if (distancia > e.getDistance()) return; // Mantém alvo atual se mais perto
        }

        // Atualiza dados do alvo
        targetName = e.getName();
        targetLastSeen = getTime();
        targetVelocity = e.getVelocity();
        targetHeading = e.getHeadingRadians();

        // Calcula posição absoluta do inimigo
        double anguloAbsoluto = Math.toRadians(getHeading() + e.getBearing());
        targetX = getX() + Math.sin(anguloAbsoluto) * distancia;
        targetY = getY() + Math.cos(anguloAbsoluto) * distancia;

        atirarComPredicao(distancia);
        broadcastPosicaoInimigo(e);
    }

    // Mira preditiva: calcula onde o inimigo ESTARÁ quando a bala chegar
    private void atirarComPredicao(double distancia) {
        double potencia = calcularPotencia(distancia);
        double velocidadeBala = 20 - 3 * potencia;

        // Tempo estimado para a bala alcançar o alvo
        double tempoVoo = distancia / velocidadeBala;

        // Posição futura estimada do inimigo
        double futuroX = targetX + Math.sin(targetHeading) * targetVelocity * tempoVoo;
        double futuroY = targetY + Math.cos(targetHeading) * targetVelocity * tempoVoo;

        // Ângulo do canhão para a posição futura
        double anguloArma = Math.atan2(futuroX - getX(), futuroY - getY());
        double desvioArma = Utils.normalRelativeAngle(anguloArma - Math.toRadians(getGunHeading()));

        setTurnGunRightRadians(desvioArma);

        // Só atira quando o canhão estiver bem alinhado
        if (Math.abs(desvioArma) < Math.toRadians(5)) {
            setFire(potencia);
        }
    }

    // Potência adaptativa: mais forte perto, mais fraco longe para maior velocidade
    private double calcularPotencia(double distancia) {
        if (distancia < 150) return 3.0;
        if (distancia < 300) return 2.5;
        if (distancia < 500) return 2.0;
        return 1.5;
    }

    // Envia posição do inimigo para os aliados
    private void broadcastPosicaoInimigo(ScannedRobotEvent e) {
        try {
            String msg = "INIMIGO:" + e.getName() + ":" + (int) targetX + ":" + (int) targetY + ":" + e.getDistance();
            broadcastMessage(msg);
        } catch (IOException ex) {
            // Falha silenciosa no broadcast
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        // Esquiva perpendicular ao tiro recebido
        strafeDirection *= -1;
        moveDirection *= -1;
        setAhead(80 * moveDirection);
        setTurnRight(90 - e.getBearing());
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1;
        setBack(60);
        setTurnRight(45 * moveDirection);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        if (!isTeammate(e.getName())) {
            setFire(3); // Tiro máximo no contato
            moveDirection *= -1;
            setBack(80);
        }
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        String msg = (String) e.getMessage();
        if (msg.startsWith("INIMIGO:")) {
            // Formato: INIMIGO:nome:x:y:distancia
            String[] partes = msg.split(":");
            if (partes.length >= 5 && targetName == null) {
                // Usa a informação do aliado para ir ao encontro do inimigo
                targetName = partes[1];
                targetX = Double.parseDouble(partes[2]);
                targetY = Double.parseDouble(partes[3]);
                targetLastSeen = getTime();
            }
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        if (e.getName().equals(targetName)) {
            targetName = null; // Alvo morto, procurar próximo
        }
        teammates.remove(e.getName());
    }

    @Override
    public void onWin(WinEvent e) {
        // Dança da vitória
        for (int i = 0; i < 5; i++) {
            setTurnRight(180);
            setAhead(50);
            execute();
            setTurnLeft(180);
            setAhead(50);
            execute();
        }
    }
}